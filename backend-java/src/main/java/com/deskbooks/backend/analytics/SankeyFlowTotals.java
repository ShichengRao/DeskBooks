package com.deskbooks.backend.analytics;

import java.math.BigDecimal;

record SankeyFlowTotals(
        BigDecimal income,
        BigDecimal expenses,
        BigDecimal growth,
        BigDecimal inflows) {
}
