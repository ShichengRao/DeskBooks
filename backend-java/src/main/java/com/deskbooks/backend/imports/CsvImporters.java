package com.deskbooks.backend.imports;

import java.util.List;

final class CsvImporters {
    private static final List<CsvImporter> IMPORTERS = List.of(
            new ChaseCreditImporter(),
            new WellsFargoCheckingImporter(),
            new AmexImporter(),
            new ContributionHistoryImporter(),
            new ChaseBankImporter(),
            new CitiCreditImporter(),
            new RunningBalanceBankImporter(),
            new PncBankImporter(),
            new DebitCreditBankImporter(),
            new UsBankImporter(),
            new ActivityBankImporter(),
            new CapitalOneCreditImporter(),
            new DiscoverCreditImporter());

    private CsvImporters() {
    }

    static List<CsvImporter> all() {
        return IMPORTERS;
    }
}
