package top.vulpine.catalog;

import top.vulpine.commons.log.LogAction;

/**
 * The log tags Catalog writes under.
 */
public enum CatalogAction implements LogAction {
    CONFIG, SETUP, SCAN, TRACK, UPDATE, INSTALL
}
