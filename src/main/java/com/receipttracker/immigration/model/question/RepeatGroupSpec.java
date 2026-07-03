package com.receipttracker.immigration.model.question;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Repeat-group config for a LIST question — the answer is a JSON array of row
 * objects; each row has the properties declared in {@link #itemFields}.
 *
 * Example (canonical-questions.json):
 * <pre>
 * "repeatGroup": {
 *   "sourceList": "dependentsJson",
 *   "maxRows": 4,
 *   "itemFields": [
 *     { "key": "name", "label": "Full Name", "type": "TEXT", "required": true },
 *     { "key": "dateOfBirth", "label": "Date of Birth", "type": "DATE" }
 *   ]
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RepeatGroupSpec {

    /** Fallback maxRows when the config omits it or sets a non-positive value. */
    public static final int DEFAULT_MAX_ROWS = 10;

    /**
     * Optional CanonicalProfile JSON-array column to prefill from
     * (e.g. "priorVisasJson", "dependentsJson"). Only declared itemFields are
     * projected out of the column. Absent → the generic store is the only
     * prefill source. Write-back always goes to the generic store — profile
     * JSON columns are never patched (no-partial-JSON-write-back rule).
     */
    private String sourceList;

    /** Rows beyond this are dropped at submit and ignored at PDF fill (WARN). */
    private int maxRows;

    /** Columns of each row, in display order */
    private List<RepeatItemField> itemFields;

    public String getSourceList()          { return sourceList; }
    public void setSourceList(String v)    { sourceList = v; }

    public int getMaxRows()                { return maxRows; }
    public void setMaxRows(int v)          { maxRows = v; }

    public List<RepeatItemField> getItemFields()        { return itemFields; }
    public void setItemFields(List<RepeatItemField> v)  { itemFields = v; }

    /** maxRows with the default applied for missing/invalid config. */
    public int effectiveMaxRows() {
        return maxRows > 0 ? maxRows : DEFAULT_MAX_ROWS;
    }
}
