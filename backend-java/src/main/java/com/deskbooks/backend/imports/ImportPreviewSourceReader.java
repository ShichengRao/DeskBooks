package com.deskbooks.backend.imports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

final class ImportPreviewSourceReader {
    ImportPreviewSource fromUpload(MultipartFile file, long accountId, String importerName) {
        try {
            String filename = file.getOriginalFilename() == null ? "uploaded.csv" : file.getOriginalFilename();
            return new ImportPreviewSource(file.getBytes(), filename, accountId, importerName);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read upload");
        }
    }

    ImportPreviewSource fromPath(ImportPathPreviewRequest body) {
        Path path = Path.of(body.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }
        try {
            return new ImportPreviewSource(
                    Files.readAllBytes(path),
                    path.getFileName().toString(),
                    body.accountId(),
                    body.importerName());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read file");
        }
    }
}
