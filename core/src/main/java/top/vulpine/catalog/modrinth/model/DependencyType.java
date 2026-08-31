package top.vulpine.catalog.modrinth.model;

import com.google.gson.annotations.SerializedName;

/**
 * How a Modrinth version relates to one of its declared dependencies.
 */
public enum DependencyType {

    /** The dependent plugin does not run without it. */
    @SerializedName("required")
    REQUIRED,

    /** Unlocks extra behaviour, but is never installed on its own. */
    @SerializedName("optional")
    OPTIONAL,

    /** Must not be installed alongside. */
    @SerializedName("incompatible")
    INCOMPATIBLE,

    /** Already shaded into the jar, so nothing has to be installed. */
    @SerializedName("embedded")
    EMBEDDED

}
