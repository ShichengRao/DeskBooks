package com.deskbooks.backend.imports;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
class ImportController {
    private final ImportEndpointService imports;

    ImportController(ImportEndpointService imports) {
        this.imports = imports;
    }

    @GetMapping("/importers")
    List<ImporterResponse> listImporters() {
        return imports.listImporters();
    }

    @GetMapping("")
    List<ImportBatchResponse> listBatches() {
        return imports.listBatches();
    }

    @PostMapping("/preview")
    ImportPreviewResponse previewUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("account_id") long accountId,
            @RequestParam(name = "importer_name", required = false) String importerName) {
        return imports.previewUpload(file, accountId, importerName);
    }

    @PostMapping("/preview-path")
    ImportPreviewResponse previewPath(@Valid @RequestBody ImportPathPreviewRequest body) {
        return imports.previewPath(body);
    }

    @PostMapping("/apply")
    ImportBatchResponse apply(@Valid @RequestBody ImportApplyRequest body) {
        return imports.apply(body);
    }

    @PostMapping("/{batchId}/rollback")
    Map<String, String> rollbackBatch(@PathVariable long batchId) {
        return imports.rollbackBatch(batchId);
    }
}
