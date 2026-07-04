package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SankeyGraph {
    private final List<String> nodeLabels = new ArrayList<>();
    private final Map<String, Integer> nodeIndex = new LinkedHashMap<>();
    private final List<SankeyLinkResponse> graphLinks = new ArrayList<>();

    int node(String name) {
        Integer existing = nodeIndex.get(name);
        if (existing != null) {
            return existing;
        }
        int index = nodeLabels.size();
        nodeIndex.put(name, index);
        nodeLabels.add(name);
        return index;
    }

    void link(int source, int target, BigDecimal value, String label) {
        graphLinks.add(new SankeyLinkResponse(source, target, value.doubleValue(), label));
    }

    List<String> nodes() {
        return nodeLabels;
    }

    List<SankeyLinkResponse> links() {
        return graphLinks;
    }
}
