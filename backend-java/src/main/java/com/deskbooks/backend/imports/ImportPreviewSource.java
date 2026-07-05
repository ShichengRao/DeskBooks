package com.deskbooks.backend.imports;

record ImportPreviewSource(byte[] data, String filename, long accountId, String importerName) {}
