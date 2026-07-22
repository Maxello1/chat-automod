package com.maxello1.chatautomod.core.history;

import java.util.List;

public record HistoryPage<T>(List<T> entries, int page, int pageSize, int totalEntries, int totalPages) {
    public HistoryPage {
        entries = List.copyOf(entries);
    }
}
