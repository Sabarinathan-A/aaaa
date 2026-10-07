package com.frauddetector.service;

import com.frauddetector.domain.Evidence;
import com.frauddetector.domain.Investigation;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.EvidenceRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.testkit.Assert;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

/**
 * Secure evidence upload: allowed types with matching content are stored and
 * readable; wrong types, spoofed content, oversize files and unknown
 * investigations are rejected; client file names cannot escape the storage
 * directory. Run via {@code ./build.sh test}.
 */
public final class EvidenceServiceTest {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("evidence-test");
        try {
            InvestigationRepository investigations = new InvestigationRepository();
            investigations.save(new Investigation("INV-1", "CLM-1", "U-1", "OPEN", null, null, Instant.now(), Instant.now()));
            EvidenceService svc = new EvidenceService(new EvidenceRepository(), investigations, dir);

            byte[] pdf = "%PDF-1.4\n% test document\n".getBytes(StandardCharsets.US_ASCII);
            Evidence e = svc.upload("INV-1", "../../etc/invoice.pdf", "application/pdf", b64(pdf), "invoice", "U-1");
            Assert.assertEquals("invoice.pdf", e.getFileName(), "path components stripped from file name");
            Assert.assertEquals((long) pdf.length, e.getSizeBytes(), "size recorded");
            Assert.assertEquals(new String(pdf, StandardCharsets.US_ASCII),
                    new String(svc.read(e), StandardCharsets.US_ASCII), "bytes round-trip");
            Assert.assertEquals(1, svc.list("INV-1").size(), "listed");
            try (var files = Files.list(dir)) {
                Assert.assertTrue(files.allMatch(p -> p.getFileName().toString().startsWith("EVD-")),
                        "stored under server-generated names only");
            }

            expect(400, () -> svc.upload("INV-1", "x.exe", "application/x-msdownload", b64(pdf), null, "U-1"));
            expect(400, () -> svc.upload("INV-1", "fake.pdf", "application/pdf", b64("MZ binary".getBytes()), null, "U-1"));
            expect(400, () -> svc.upload("INV-1", "bad.txt", "text/plain", "not base64 !!!", null, "U-1"));
            expect(404, () -> svc.upload("INV-404", "a.txt", "text/plain", b64("hello".getBytes()), null, "U-1"));
            byte[] big = new byte[(int) EvidenceService.MAX_BYTES + 10];
            big[0] = 'a';
            expect(413, () -> svc.upload("INV-1", "big.txt", "text/plain", b64(big), null, "U-1"));
            Assert.assertEquals("evidence.txt", EvidenceService.sanitize("...", ".txt"), "dot-only name falls back");
            Assert.assertEquals("a_b.png", EvidenceService.sanitize("C:\\tmp\\a b.png", ".png"), "windows path + spaces");
        } finally {
            try (var files = Files.list(dir)) {
                files.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
        System.out.println("EvidenceServiceTest OK");
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static void expect(int status, Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected ApiException " + status);
        } catch (ApiException e) {
            Assert.assertEquals(status, e.getStatusCode(), "status for: " + e.getMessage());
        }
    }
}
