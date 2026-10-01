package com.ecobank.rccportal.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    @GetMapping("/")
    public String index() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/administration")
    public String administration() {
        return "administration";
    }

    @GetMapping("/users")
    public String users() {
        // Fusionné dans Administration — redirige pour ne pas casser un ancien favori/lien.
        return "redirect:/administration";
    }

    @GetMapping("/performance")
    public String performance() {
        return "performance";
    }

    @GetMapping("/workflow")
    public String workflow() {
        return "workflow";
    }

    /** Fond de carte de l'onglet « Agences / Carte » — voir rcc.map.tile-url (application.yml). */
    @Value("${rcc.map.tile-url:}")
    private String mapTileUrl;

    @GetMapping("/knowledge")
    public String knowledge(Model model) {
        model.addAttribute("mapTileUrl", mapTileUrl);
        return "knowledge";
    }

    @GetMapping("/procedures")
    public String procedures() {
        return "procedures";
    }

    @GetMapping("/mail-templates")
    public String mailTemplates() {
        return "mail-templates";
    }

    @GetMapping("/training")
    public String training() {
        return "training";
    }

    @GetMapping("/rh")
    public String rhPortal(Model model) {
        model.addAttribute("viewer", false);
        return "hr-parcours";
    }


    /** Ancien favori conservé pour compatibilité. Le portail RH est désormais accessible sous /rh. */
    @GetMapping("/hr-parcours")
    public String hrParcours() {
        return "redirect:/rh";
    }

    @GetMapping("/training/lesson/{id}")
    public String trainingLesson(@org.springframework.web.bind.annotation.PathVariable Integer id) {
        return "training-lesson";
    }

    @GetMapping("/games")
    public String games() {
        return "games";
    }

    @GetMapping("/procedures/{id}/play")
    public String procedurePlay(@org.springframework.web.bind.annotation.PathVariable Integer id) {
        return "procedure-play";
    }

    @GetMapping("/data-analysis")
    public String dataAnalysis() {
        return "data-analysis";
    }

    @GetMapping("/shift")
    public String shift() {
        return "shift";
    }

    @GetMapping("/qa")
    public String qa() {
        return "qa";
    }

    @GetMapping("/qa-supervisor")
    public String qaSupervisor() {
        return "qa-supervisor";
    }

    @GetMapping("/reports")
    public String reports() {
        return "reports";
    }

    /** Portail Superviseur : les vues du portail RH en lecture seule, plus Supervision, Qualité et CRM Outbound. */
    @GetMapping("/supervisor")
    public String supervisor(Model model) {
        model.addAttribute("viewer", true);
        return "hr-parcours";
    }

    @GetMapping("/team-leader")
    public String teamLeader() {
        return "team-leader";
    }

    /** Portail Outbound = équipe Digitalisation ; la Télévente a son propre portail (même socle, contenu Télévente). */
    @GetMapping("/outbound-dashboard")
    public String outboundDashboard(org.springframework.ui.Model model) {
        model.addAttribute("outboundTeam", "DIGITALISATION");
        return "outbound-dashboard";
    }

    @GetMapping("/portail-televente")
    public String televentePortal(org.springframework.ui.Model model) {
        model.addAttribute("outboundTeam", "TELEVENTE");
        return "outbound-dashboard";
    }

    /** Portails des agents des canaux digitaux : même page, indicateurs et couleurs propres au canal. */
    @GetMapping("/portail-tchat")
    public String chatPortal(org.springframework.ui.Model model) {
        model.addAttribute("channel", "TCHAT");
        return "digital-agent";
    }

    /** Portail des agents Inbound Mail (mails, CIS, rappels) : même socle que Réseaux sociaux / Rafiki. */
    @GetMapping("/portail-mail")
    public String mailPortal(org.springframework.ui.Model model) {
        model.addAttribute("channel", "MAIL");
        return "digital-agent";
    }

    @GetMapping("/portail-rafiki")
    public String rafikiPortal(org.springframework.ui.Model model) {
        model.addAttribute("channel", "RAFIKI");
        return "digital-agent";
    }

    @GetMapping("/audit")
    public String audit() {
        return "audit";
    }

    @GetMapping("/settings")
    public String settings() {
        return "settings";
    }

    @GetMapping("/notifications")
    public String notifications() {
        return "notifications";
    }

    @GetMapping("/mon-rcc")
    public String monRcc() {
        return "mon-rcc";
    }

    @GetMapping("/translator")
    public String translator() {
        return "translator";
    }

    /** Portail Agence — caissiers et gestionnaires clientèle : mêmes référentiels que le RCC
     *  (cartographie des cas, procédures, base de connaissances, cartes, escalades) + outils locaux. */
    @GetMapping("/agence")
    public String agencePortal() {
        return "agence";
    }
}