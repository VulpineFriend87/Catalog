package top.vulpine.catalog.tracking.model;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import top.vulpine.catalog.modrinth.model.ReleaseChannel;

/**
 * The settings a plugin starts with when Catalog adopts or installs it.
 *
 * <p>Copied into each record rather than consulted later, so changing the server-wide default never
 * moves the ground under plugins that are already tracked.</p>
 */
@Getter
@Builder
@Accessors(fluent = true)
public final class TrackingDefaults {

    @Builder.Default
    private final ReleaseChannel channel = ReleaseChannel.RELEASE;

    @Builder.Default
    private final boolean autoUpdate = false;

    @Builder.Default
    private final int soakMinutes = TrackedPlugin.INHERIT_SOAK;

    /**
     * The settings a server gets when it has configured nothing.
     *
     * @return release channel, no automatic updates
     */
    public static TrackingDefaults standard() {
        return TrackingDefaults.builder().build();
    }

}
