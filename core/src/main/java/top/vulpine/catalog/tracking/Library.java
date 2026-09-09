package top.vulpine.catalog.tracking;

import top.vulpine.catalog.CatalogAction;
import top.vulpine.catalog.Errors;
import top.vulpine.catalog.jar.JarScanner;
import top.vulpine.catalog.jar.model.InstalledJar;
import top.vulpine.catalog.jar.model.ScanResult;
import top.vulpine.catalog.modrinth.ModrinthClient;
import top.vulpine.catalog.modrinth.model.ModrinthProject;
import top.vulpine.catalog.modrinth.model.ModrinthVersion;
import top.vulpine.catalog.platform.Platform;
import top.vulpine.catalog.tracking.model.ReconcileReport;
import top.vulpine.catalog.tracking.model.TrackedPlugin;
import top.vulpine.catalog.tracking.model.TrackingDefaults;
import top.vulpine.commons.log.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * What is in the plugins folder, and which of it Catalog manages.
 *
 * <p>Blocks, so it must be called off the server main thread.</p>
 */
public final class Library {

    private final Platform platform;
    private final ModrinthClient modrinth;
    private final TrackingStore tracking;
    private final IgnoreList ignored;
    private final Supplier<TrackingDefaults> defaults;
    private final BooleanSupplier autoTrack;

    private volatile int unmanaged;

    public Library(Platform platform, ModrinthClient modrinth, TrackingStore tracking,
                   IgnoreList ignored, Supplier<TrackingDefaults> defaults,
                   BooleanSupplier autoTrack) {
        this.platform = platform;
        this.modrinth = modrinth;
        this.tracking = tracking;
        this.ignored = ignored;
        this.defaults = defaults;
        this.autoTrack = autoTrack;
    }

    /**
     * @return how many jars in the folder are not on Modrinth
     */
    public int unmanaged() {
        return unmanaged;
    }

    /**
     * Hashes the plugins folder, identifies what Modrinth knows, and reconciles the tracking file.
     *
     * @return true if the scan completed, false if Modrinth could not be reached
     */
    public boolean index() {

        long started = System.currentTimeMillis();
        ScanResult scan = new JarScanner(platform.pluginsFolder()).scan();

        List<String> hashes = new ArrayList<>();

        for (InstalledJar jar : scan.jars()) {
            if (jar.sha512() != null) {
                hashes.add(jar.sha512());
            }
        }

        Logger.debug(CatalogAction.SCAN, "Hashed " + hashes.size() + " jars in "
                + (System.currentTimeMillis() - started) + "ms.");

        for (InstalledJar jar : scan.unreadable()) {
            Logger.warn(CatalogAction.SCAN, "Could not read " + jar.fileName()
                    + ", so it is not indexed. On Windows this usually means the file is locked.");
        }

        Map<String, ModrinthVersion> identified;

        try {
            identified = modrinth.identify(hashes).join();
        } catch (Exception e) {
            // Without an answer every tracked plugin would look unidentifiable, and reconciling on
            // that would untrack the entire server over a network blip.
            Logger.warn(CatalogAction.SCAN, "Could not reach Modrinth, so nothing was reconciled: "
                    + Errors.rootMessage(e));
            return false;
        }

        Reconciler reconciler = new Reconciler(tracking, ignored, defaults.get(),
                autoTrack.getAsBoolean());

        ReconcileReport report = reconciler.reconcile(scan, identified);
        boolean named = nameTrackedPlugins();

        if (report.hasChanges() || named) {

            try {
                tracking.save();
            } catch (TrackingException e) {
                Logger.error(CatalogAction.TRACK, e.getMessage());
            }
        }

        unmanaged = report.unknown().size();

        describe(report, scan);
        return true;
    }

    /**
     * Fills in the human names of tracked plugins that only have a project id.
     *
     * @return true if anything was named, so the caller knows to save
     */
    private boolean nameTrackedPlugins() {

        List<String> missing = new ArrayList<>();

        for (TrackedPlugin plugin : tracking.all()) {
            if (plugin.name() == null && plugin.projectId() != null) {
                missing.add(plugin.projectId());
            }
        }

        if (missing.isEmpty()) {
            return false;
        }

        try {

            for (ModrinthProject project : modrinth.projects(missing).join()) {

                TrackedPlugin plugin = tracking.byProjectId(project.id());

                if (plugin != null) {
                    plugin.name(project.title());
                    plugin.slug(project.slug());
                }
            }

            return true;

        } catch (Exception e) {
            // Names are cosmetic; project ids still identify everything correctly without them.
            Logger.debug(CatalogAction.TRACK, "Could not fetch project names: "
                    + Errors.rootMessage(e));
            return false;
        }
    }

    /**
     * Says what the scan found, at the volume each outcome deserves.
     */
    private void describe(ReconcileReport report, ScanResult scan) {

        Logger.info(CatalogAction.SCAN, "Indexed " + scan.jars().size() + " jars, tracking "
                + tracking.size() + " plugins.");

        if (!report.adopted().isEmpty()) {
            Logger.info(CatalogAction.TRACK, "Adopted " + report.adopted().size()
                    + " plugins: " + names(report.adopted()));
        }

        if (!report.applied().isEmpty()) {
            Logger.info(CatalogAction.UPDATE, "Updates applied on this start: "
                    + names(report.applied()));
        }

        for (TrackedPlugin plugin : report.notApplied()) {
            Logger.warn(CatalogAction.UPDATE, plugin.displayName() + " is still "
                    + plugin.versionNumber() + ": the staged build was not taken from "
                    + platform.stagingName()
                    + ". It is still there and will be tried again on the next start.");
        }

        if (!report.moved().isEmpty()) {
            Logger.info(CatalogAction.TRACK, "Replaced by hand since last start: "
                    + names(report.moved()));
        }

        if (!report.renamed().isEmpty()) {
            Logger.debug(CatalogAction.TRACK, "Renamed by hand since last start: "
                    + names(report.renamed()));
        }

        if (!report.removed().isEmpty()) {
            Logger.info(CatalogAction.TRACK, "No longer installed, so no longer tracked: "
                    + names(report.removed()));
        }

        if (!report.unknown().isEmpty()) {
            Logger.debug(CatalogAction.TRACK, report.unknown().size()
                    + " jars are not on Modrinth and will be left alone.");
        }

        if (!report.notAdopted().isEmpty()) {
            Logger.info(CatalogAction.TRACK, report.notAdopted().size()
                    + " recognised plugins were not adopted because auto_track is off.");
        }

        for (TrackedPlugin plugin : report.orphaned()) {
            Logger.warn(CatalogAction.TRACK, plugin.displayName()
                    + " was replaced with a different plugin, so Catalog stopped tracking it.");
        }

        for (InstalledJar jar : report.conflicting()) {
            Logger.warn(CatalogAction.TRACK, jar.fileName()
                    + " is a second jar for a project that is already tracked.");
        }
    }

    private static String names(List<TrackedPlugin> plugins) {

        List<String> names = new ArrayList<>();

        for (TrackedPlugin plugin : plugins) {
            names.add(plugin.displayName());
        }

        return String.join(", ", names);
    }

}
