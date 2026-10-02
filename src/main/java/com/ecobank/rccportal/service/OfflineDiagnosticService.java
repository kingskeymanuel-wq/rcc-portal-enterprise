package com.ecobank.rccportal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Administration → Maintenance → « Fonctionnement sans Internet » : état de chaque service dont le portail a besoin
 * sur un serveur isolé (base, authentification, messagerie, IA locale, transcription, traduction, OCR, carte), et
 * liste des services Internet encore actifs. Lecture seule, tests rapides (quelques secondes au plus).
 */
@Service
public class OfflineDiagnosticService {

    /** OK = prêt · WARN = fonctionne en mode dégradé · KO = injoignable · OFF = coupé (normal hors ligne) · ON = Internet requis. */
    public record Check(String group, String name, String status, String detail, String fix) {}

    public record Report(boolean offlineMode, int ready, int total, int percent, List<Check> internal, List<Check> internet, LocalDateTime checkedAt) {}

    private final OfflineMode offline;
    private final LocalAiClient localAi;
    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${spring.mail.host:}") private String mailHost;
    @Value("${spring.mail.port:25}") private int mailPort;
    @Value("${rcc.auth.gateway.login-url:}") private String gatewayUrl;
    @Value("${rcc.auth.ad.url:}") private String adUrl;
    @Value("${rcc.translation.libretranslate.url:}") private String libreUrl;
    @Value("${rcc.translation.offline.python-executable:}") private String argosPython;
    @Value("${rcc.translation.mymemory.enabled:true}") private boolean myMemory;
    @Value("${rcc.translation.deepl.api-key:}") private String deeplKey;
    @Value("${rcc.translation.azure.key:}") private String azureTranslatorKey;
    @Value("${rcc.ocr.python-executable:}") private String ocrPython;
    @Value("${rcc.ocr.script-path:}") private String ocrScript;
    @Value("${rcc.map.tile-url:}") private String tileUrl;
    @Value("${quality.ai.anthropic-key:}") private String anthropicKey;
    @Value("${quality.ai.azure-openai-key:}") private String azureOpenAiKey;
    @Value("${quality.ai.azure-speech-key:}") private String azureSpeechKey;
    @Value("${graph.client-secret:}") private String graphSecret;
    @Value("${copilot.enabled:false}") private boolean copilot;
    @Value("${websearch.enabled:true}") private boolean websearch;

    public OfflineDiagnosticService(OfflineMode offline, LocalAiClient localAi, JdbcTemplate jdbc) {
        this.offline = offline;
        this.localAi = localAi;
        this.jdbc = jdbc;
    }

    public Report run() {
        boolean off = offline.isEnabled();
        List<Check> in = new ArrayList<>();

        // ── Réseau interne indispensable ──
        in.add(database());
        in.add(endpoint("Socle", "Authentification (passerelle SAGED)", gatewayUrl, "Vérifier l'accès réseau au serveur SAGED (RCC_AUTH_GATEWAY_LOGIN_URL)."));
        in.add(endpoint("Socle", "Annuaire AD", adUrl, "Vérifier l'accès réseau au service AD interne."));
        in.add(mailHost == null || mailHost.isBlank() || "localhost".equalsIgnoreCase(mailHost)
                ? new Check("Socle", "Messagerie (SMTP interne)", "WARN", "Aucun serveur SMTP interne renseigné (" + (mailHost == null ? "" : mailHost) + ")", "RCC_MAIL_HOST / RCC_MAIL_PORT : serveur Exchange ou relais SMTP interne.")
                : tcp("Socle", "Messagerie (SMTP interne)", mailHost, mailPort, "Vérifier RCC_MAIL_HOST / RCC_MAIL_PORT."));

        // ── Remplaçants locaux des services Internet ──
        in.add(localText());
        in.add(localVision());
        in.add(whisper());
        in.add(translation());
        in.add(ocr());
        in.add(map());

        // ── Services hébergés sur Internet ──
        List<Check> net = new ArrayList<>();
        net.add(internet("Anthropic (Claude)", notBlank(anthropicKey), off, "remplacé par l'IA locale"));
        net.add(internet("Azure OpenAI", notBlank(azureOpenAiKey), off, "remplacé par l'IA locale"));
        net.add(internet("Azure AI Speech (transcription)", notBlank(azureSpeechKey), off, "remplacé par Whisper local"));
        net.add(internet("Microsoft Graph (Teams / Outlook)", notBlank(graphSecret), off, "messagerie interne et notifications du portail"));
        net.add(internet("Copilot Studio", copilot, off, "RAF répond avec la base interne"));
        net.add(internet("Recherche web (RAF)", websearch, off, "RAF répond avec la base interne"));
        net.add(internet("Traducteurs en ligne (MyMemory, DeepL, Azure)", myMemory || notBlank(deeplKey) || notBlank(azureTranslatorKey), off, "LibreTranslate local"));
        net.add(internet("Fond de carte OpenStreetMap", OfflineMode.isInternetUrl(tileUrl), off, "serveur de tuiles interne ou fond vectoriel local"));

        int ready = (int) in.stream().filter(c -> "OK".equals(c.status())).count();
        int percent = in.isEmpty() ? 0 : Math.round(ready * 100f / in.size());
        return new Report(off, ready, in.size(), percent, in, net, LocalDateTime.now());
    }

    private Check database() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new Check("Socle", "Base de données SQL Server", "OK", "Connexion active", null);
        } catch (RuntimeException e) {
            return new Check("Socle", "Base de données SQL Server", "KO", e.getMessage(), "Vérifier la connexion à la base (spring.datasource).");
        }
    }

    private Check localText() {
        String name = "IA locale — texte (réécriture, campagnes, analyses, QA)";
        if (!localAi.isConfigured()) {
            return new Check("IA", name, "WARN", "Non configurée : repli sur les règles locales, analyses QA indisponibles",
                    "Installer Ollama (kit scripts/offline-kit) puis RCC_LOCAL_AI_URL=http://127.0.0.1:11434.");
        }
        List<String> models = localAi.installedModels();
        if (models.isEmpty()) return new Check("IA", name, "KO", "Serveur injoignable : " + localAi.url(), "Démarrer Ollama (tâche RCC-Ollama) et vérifier l'URL.");
        boolean has = models.stream().anyMatch(m -> m.equalsIgnoreCase(localAi.model()) || m.startsWith(localAi.model() + ":"));
        return has ? new Check("IA", name, "OK", "Modèle « " + localAi.model() + " » prêt", null)
                : new Check("IA", name, "KO", "Modèle « " + localAi.model() + " » absent (installés : " + String.join(", ", models) + ")",
                "Copier le modèle avec le kit hors ligne, ou corriger RCC_LOCAL_AI_MODEL.");
    }

    private Check localVision() {
        String name = "IA locale — lecture d'images (import KPI par capture)";
        if (!localAi.isVisionConfigured()) {
            return new Check("IA", name, "WARN", "Non configurée : l'OCR local prend le relais s'il est installé", "Facultatif : RCC_LOCAL_AI_VISION_MODEL=qwen2.5vl:7b.");
        }
        boolean has = localAi.installedModels().stream().anyMatch(m -> m.startsWith(localAi.visionModel()));
        return has ? new Check("IA", name, "OK", "Modèle « " + localAi.visionModel() + " » prêt", null)
                : new Check("IA", name, "KO", "Modèle « " + localAi.visionModel() + " » absent", "Copier le modèle avec le kit hors ligne.");
    }

    private Check whisper() {
        String name = "Transcription des appels QA (Whisper local)";
        if (!localAi.isWhisperConfigured()) {
            return new Check("IA", name, "WARN", "Non configurée : transcription QA indisponible hors ligne",
                    "Installer le serveur Whisper (kit) puis RCC_LOCAL_WHISPER_URL=http://127.0.0.1:8090.");
        }
        return http("IA", name, localAi.whisperUrl() + "/health", "Démarrer la tâche RCC-Whisper.");
    }

    private Check translation() {
        String name = "Traduction (LibreTranslate local)";
        if (notBlank(libreUrl) && !OfflineMode.isInternetUrl(libreUrl)) {
            Check c = http("Outils", name, libreUrl.replaceAll("/+$", "") + "/languages", "Démarrer la tâche RCC-LibreTranslate (kit scripts/libretranslate-offline).");
            if ("OK".equals(c.status()) || !notBlank(argosPython)) return c;
        }
        if (notBlank(argosPython)) return new Check("Outils", name, Files.exists(Path.of(argosPython)) ? "OK" : "KO", "Argos Translate en direct (" + argosPython + ")", "Vérifier rcc.translation.offline.python-executable.");
        return new Check("Outils", name, "WARN", "Aucune traduction locale : glossaire de formules seulement", "Installer le kit scripts/libretranslate-offline.");
    }

    private Check ocr() {
        String name = "OCR local (PaddleOCR — import KPI par capture)";
        if (!notBlank(ocrPython) || !notBlank(ocrScript)) {
            return new Check("Outils", name, "WARN", "Non configuré", "Installer PaddleOCR (kit) puis RCC_OCR_PYTHON_EXECUTABLE et RCC_OCR_SCRIPT_PATH.");
        }
        boolean ok = Files.exists(Path.of(ocrPython)) && Files.exists(Path.of(ocrScript));
        return new Check("Outils", name, ok ? "OK" : "KO", ok ? "Python et script trouvés" : "Fichiers introuvables : " + ocrPython + " / " + ocrScript, ok ? null : "Vérifier les chemins.");
    }

    private Check map() {
        String name = "Fond de carte des agences";
        if (!notBlank(tileUrl)) return new Check("Outils", name, "OK", "Fond vectoriel local (pays, marqueurs) — sans rues", null);
        if (OfflineMode.isInternetUrl(tileUrl)) {
            return new Check("Outils", name, "WARN", "Tuiles OpenStreetMap (Internet) : fond vectoriel local utilisé hors ligne",
                    "Facultatif : serveur de tuiles interne → RCC_MAP_TILE_URL, sinon laisser vide.");
        }
        return new Check("Outils", name, "OK", "Serveur de tuiles interne : " + tileUrl, null);
    }

    private Check internet(String name, boolean configured, boolean off, String replacement) {
        if (!configured) return new Check("Internet", name, "OFF", "Non configuré — " + replacement, null);
        if (off) return new Check("Internet", name, "OFF", "Coupé par le mode hors ligne — " + replacement, null);
        return new Check("Internet", name, "ON", "Actif : nécessite Internet", "RCC_OFFLINE=true pour le couper — " + replacement + ".");
    }

    private Check endpoint(String group, String name, String url, String fix) {
        if (!notBlank(url)) return new Check(group, name, "WARN", "Non configuré", fix);
        try {
            URI u = URI.create(url);
            int port = u.getPort() > 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
            Check c = tcp(group, name, u.getHost(), port, fix);
            return OfflineMode.isInternetUrl(url) && "OK".equals(c.status())
                    ? new Check(group, name, "WARN", c.detail() + " — adresse sur Internet", fix) : c;
        } catch (IllegalArgumentException e) {
            return new Check(group, name, "KO", "URL invalide : " + url, fix);
        }
    }

    private Check tcp(String group, String name, String host, int port, String fix) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            return new Check(group, name, "OK", "Joignable (" + host + ":" + port + ")", null);
        } catch (Exception e) {
            return new Check(group, name, "KO", "Injoignable (" + host + ":" + port + ")", fix);
        }
    }

    private Check http(String group, String name, String url, String fix) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() < 400 ? new Check(group, name, "OK", "Répond (" + url + ")", null)
                    : new Check(group, name, "KO", "HTTP " + r.statusCode() + " (" + url + ")", fix);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check(group, name, "KO", "Interrompu", fix);
        } catch (Exception e) {
            return new Check(group, name, "KO", "Injoignable (" + url + ")", fix);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
