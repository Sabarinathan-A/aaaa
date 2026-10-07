package com.frauddetector.notify;

import com.frauddetector.domain.Notification;
import com.frauddetector.domain.User;
import com.frauddetector.repository.UserRepository;
import com.frauddetector.service.NotificationService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Email delivery for notifications (PRD section 22).
 *
 * <p>Every notification is rendered as an RFC 5322 message addressed to the
 * ACTIVE users holding the target role and written to an outbox directory
 * ({@code data/outbox/*.eml}), which works offline and doubles as a delivery log.
 * When {@code SMTP_HOST} is configured the message is also sent over SMTP
 * (optional STARTTLS + AUTH LOGIN) on a background thread, so a slow or
 * unreachable mail server never blocks the API.
 */
public final class EmailNotifier implements NotificationService.ExternalNotifier {

    private final UserRepository users;
    private final Path outbox;
    private final SmtpConfig smtp;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smtp-sender");
        t.setDaemon(true);
        return t;
    });

    /** SMTP settings; {@code host == null} disables network delivery. */
    public record SmtpConfig(String host, int port, String username, String password, String from, boolean startTls) {

        public static SmtpConfig fromEnv() {
            String host = env("SMTP_HOST");
            int port = Integer.parseInt(orElse(env("SMTP_PORT"), "587"));
            return new SmtpConfig(host, port, env("SMTP_USERNAME"), env("SMTP_PASSWORD"),
                    orElse(env("SMTP_FROM"), "fraud-detector@localhost"),
                    !"false".equalsIgnoreCase(env("SMTP_STARTTLS")));
        }

        private static String env(String k) {
            String v = System.getenv(k);
            return v == null || v.isBlank() ? null : v.trim();
        }

        private static String orElse(String v, String d) {
            return v == null ? d : v;
        }
    }

    public EmailNotifier(UserRepository users, Path outbox, SmtpConfig smtp) {
        this.users = users;
        this.outbox = outbox;
        this.smtp = smtp;
    }

    @Override
    public String name() {
        return smtp.host() == null ? "email-outbox" : "email-smtp(" + smtp.host() + ")";
    }

    @Override
    public void deliver(Notification n) {
        List<String> to = users.findAll().stream()
                .filter(u -> u.getRole() == n.getTargetRole() && !"DISABLED".equals(u.getStatus()))
                .map(User::getEmail)
                .toList();
        if (to.isEmpty()) {
            return;
        }
        String subject = "[" + n.getSeverity() + "] " + n.getType() + " - claim " + n.getClaimId();
        String message = "From: " + smtp.from() + "\r\n"
                + "To: " + String.join(", ", to) + "\r\n"
                + "Subject: " + sanitizeHeader(subject) + "\r\n"
                + "Date: " + ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME) + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + n.getMessage() + "\r\n";
        try {
            Files.createDirectories(outbox);
            Files.writeString(outbox.resolve(n.getId() + ".eml"), message, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not write outbox: " + e.getMessage(), e);
        }
        if (smtp.host() != null) {
            sender.submit(() -> {
                try {
                    send(to, message);
                } catch (IOException | RuntimeException e) {
                    System.err.println("WARN: SMTP delivery of " + n.getId() + " failed: " + e.getMessage());
                }
            });
        }
    }

    private static String sanitizeHeader(String v) {
        return v.replaceAll("[\\r\\n]", " ");
    }

    /** Minimal SMTP client: EHLO, optional STARTTLS, optional AUTH LOGIN, MAIL/RCPT/DATA. */
    private void send(List<String> to, String message) throws IOException {
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(smtp.host(), smtp.port()), 10_000);
            plain.setSoTimeout(15_000);
            Socket socket = plain;
            BufferedReader in = reader(socket);
            OutputStream out = socket.getOutputStream();
            expect(in, 220);
            command(out, in, "EHLO fraud-detector", 250);
            if (smtp.startTls()) {
                command(out, in, "STARTTLS", 220);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(plain, smtp.host(), smtp.port(), true);
                tls.startHandshake();
                socket = tls;
                in = reader(socket);
                out = socket.getOutputStream();
                command(out, in, "EHLO fraud-detector", 250);
            }
            if (smtp.username() != null) {
                command(out, in, "AUTH LOGIN", 334);
                command(out, in, b64(smtp.username()), 334);
                command(out, in, b64(smtp.password() == null ? "" : smtp.password()), 235);
            }
            command(out, in, "MAIL FROM:<" + smtp.from() + ">", 250);
            for (String rcpt : to) {
                command(out, in, "RCPT TO:<" + rcpt + ">", 250);
            }
            command(out, in, "DATA", 354);
            // Dot-stuff lines beginning with '.' (RFC 5321 section 4.5.2).
            String body = message.replace("\r\n.", "\r\n..");
            out.write((body + "\r\n.\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            expect(in, 250);
            command(out, in, "QUIT", 221);
        }
    }

    private static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void command(OutputStream out, BufferedReader in, String cmd, int code) throws IOException {
        out.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        expect(in, code);
    }

    private static void expect(BufferedReader in, int code) throws IOException {
        String line;
        do {
            line = in.readLine();
            if (line == null) {
                throw new IOException("SMTP connection closed");
            }
        } while (line.length() > 3 && line.charAt(3) == '-'); // multi-line reply
        if (!line.startsWith(String.valueOf(code))) {
            throw new IOException("SMTP expected " + code + " but got: " + line);
        }
    }
}
