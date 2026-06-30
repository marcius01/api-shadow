package tech.skullprogrammer.model;

import lombok.Builder;
import lombok.Data;

/**
 * Describes how pagination is expressed in a list endpoint's 200 response schema.
 * All fields are nullable — only the ones actually found in the schema are populated.
 */
@Data
@Builder
public class PaginationShape {

    /** Name of the array property containing entities, e.g. "data", "content". Null for raw-array responses. */
    private String arrayField;
    /** Field name matching totalElements / total / totalCount. */
    private String totalElementsField;
    /** Field name matching totalPages / pageCount. */
    private String totalPagesField;
    /** Field name matching currentPage / page / pageNumber / pageIndex. */
    private String currentPageField;
    /** Field name matching size / pageSize / limit. */
    private String pageSizeField;
}
