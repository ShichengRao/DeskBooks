package com.deskbooks.backend.planning;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

final class JournalRows {
    static final String BODY_MARKDOWN = "body_markdown";
    static final String CHANGE_SUMMARY = "change_summary";
    static final String ENTRY_DATE = "entry_date";
    static final String GOAL_ID = "goal_id";
    static final String JOURNAL_NOT_FOUND = "journal entry not found";
    static final String TITLE = "title";

    private JournalRows() {
    }

    static JournalEntryResponse entryFrom(ResultSet rs) throws SQLException {
        return new JournalEntryResponse(
                rs.getLong("id"),
                LocalDate.parse(rs.getString(ENTRY_DATE)),
                rs.getString(TITLE),
                rs.getString(BODY_MARKDOWN),
                PlanningRows.nullableLong(rs, GOAL_ID),
                PlanningRows.localDateTime(rs, "created_at"),
                PlanningRows.localDateTime(rs, "updated_at"));
    }

    static JournalEntryRevisionResponse revisionFrom(ResultSet rs) throws SQLException {
        return new JournalEntryRevisionResponse(
                rs.getLong("id"),
                rs.getLong("entry_id"),
                rs.getString(TITLE),
                rs.getString(BODY_MARKDOWN),
                PlanningRows.localDate(rs, ENTRY_DATE),
                PlanningRows.nullableLong(rs, GOAL_ID),
                PlanningRows.localDateTime(rs, "changed_at"),
                rs.getString(CHANGE_SUMMARY));
    }
}
