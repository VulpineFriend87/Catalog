package top.vulpine.catalog.modrinth.model;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.List;

/**
 * A page of search results, with enough information to page through the rest.
 */
@Getter
@Accessors(fluent = true)
public final class SearchResults {

    private List<SearchHit> hits;
    private int offset;
    private int limit;
    private int totalHits;

    /**
     * The hits on this page.
     *
     * @return the hits, never null
     */
    public List<SearchHit> hits() {
        return hits == null ? Collections.emptyList() : hits;
    }

    /**
     * Whether more results exist past this page.
     *
     * @return true if another page can be requested
     */
    public boolean hasMore() {
        return offset + hits().size() < totalHits;
    }

}
