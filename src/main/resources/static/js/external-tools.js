"use strict";

/**
 * Répertoire des outils/portails externes utilisés par les agents (banque en
 * ligne, CRM, sécurité...). Liste statique de référence — change rarement,
 * pas besoin d'un backend dédié. Partagée entre Accueil et Base de
 * connaissance via window.RccExternalTools.render(containerId).
 */
window.RccExternalTools = (function () {

    var CATEGORIES = [
        {
            title: "Mobile Money & Wallet",
            icon: "bi-wallet2",
            color: "#0d6efd",
            tools: [
                { name: "Ecobank Mobile Banking Portal", host: "cellulantwallet.ecobank.com:8446", url: "https://cellulantwallet.ecobank.com:8446/wallet-ui-service.aws-shared-mobileapp-prod/web/site/login" },
                { name: "Mobile Money Portal", host: "10.8.179.35:7005", url: "http://10.8.179.35:7005/mobilemoneyportal/app/login.xhtml" },
                { name: "Agent Onboarding Portal", host: "10.8.161.65:7003" }
            ]
        },
        {
            title: "Banque en ligne & Comptes",
            icon: "bi-bank",
            color: "#0d6efd",
            tools: [
                { name: "CHECKING Plus — Cote d'Ivoire", host: "epg-eci-apps01" },
                { name: "Internet Banking Ecobank", host: "internetbanking.ecobank.com" },
                { name: "Ecobank OMNIPLUS", host: "10.8.167.16:9081" },
                { name: "Ecobank Particuliers — CI", host: "secure.ecobank.com", url: "https://secure.ecobank.com/ci/personal-banking/everyday-banking/login" }
            ]
        },
        {
            title: "Cartes & Paiements",
            icon: "bi-credit-card",
            color: "#6f42c1",
            tools: [
                { name: "Onafriq — Visa Merchant Lite", host: "cards.onafriqpartners.com", url: "https://cards.onafriqpartners.com/Visa/MerchantLite/UserLogin.aspx?ReturnUrl=%2fVisa%2fMerchantLite" }
            ]
        },
        {
            title: "Centre de Contact & CRM",
            icon: "bi-headset",
            color: "#0dcaf0",
            tools: [
                { name: "Cisco Finesse (poste agent)", host: "epg-ucce-fin2.ecobank.group", url: "https://epg-ucce-fin2.ecobank.group/desktop/container/?locale=fr_FR" },
                { name: "Sparkcentral by Hootsuite", host: "app-eu.sparkcentral.com" },
                { name: "Microsoft Dynamics CRM", host: "login.microsoftonline.com" },
                { name: "Engage — Dynamics CRM (incident)", host: "ecobankcrm.crm4.dynamics.com", url: "https://login.microsoftonline.com/6400df67-1817-484e-84ae-ed3b97ca1620/oauth2/authorize?client_id=00000007-0000-0000-c000-000000000000&response_type=code+id_token&scope=openid+profile&state=OpenIdConnect.AuthenticationProperties%3dMAAAACcxTMiN0BHxgGgADTpIaFtDfPYFrUk1cOoRRBfjxq6-6ayFINzrLE5VdQ-3utjvBwEAAAABAAAACS5yZWRpcmVjdKQBaHR0cHM6Ly9lY29iYW5rY3JtLmNybTQuZHluYW1pY3MuY29tL21haW4uYXNweD9hcHBpZD1jYTJjODNmMy04ZGJiLWViMTEtODIzNS0wMDBkM2E0NDIwNjcmcGFnZXR5cGU9ZW50aXR5cmVjb3JkJmV0bj1pbmNpZGVudCZpZD1kZGNiMWEzOC1mNDliLTQwYTMtYWU2NC03ZDdiYjNjM2ZmOGE%26ReplyUrl%3dMAAAACcxTMiN0BHxgGgADTpIaFtxKPfRtf0zUabdfCXJ3djIWyn%252fnSYhFJywKgpZtglL6Gh0dHBzOi8vYW1zLS1ldXJjcm1saXZlc2c2MTUuY3JtNC5keW5hbWljcy5jb20v%26RedirectTo%3dMAAAACcxTMiN0BHxgGgADTpIaFtqCTW9jfHMPgfbrreiJQqAIvFURZ2ZxsprV5NL7VBPm2h0dHBzOi8vZWNvYmFua2NybS5jcm00LmR5bmFtaWNzLmNvbS8%253d%26RedirectToForMcas%3dhttps%253a%252f%252fecobankcrm.crm4.dynamics.com%252fmain.aspx%253fappid%253dca2c83f3-8dbb-eb11-8235-000d3a442067%2526pagetype%253dentityrecord%2526etn%253dincident%2526id%253dddcb1a38-f49b-40a3-ae64-7d7bb3c3ff8a&response_mode=form_post&nonce=639214623513074754.ZTk0ZmRkMTgtYzE5ZS00ZGIzLWJhM2QtNzkzYjQyNTRkZmIzZTM5NGIxNjUtNzFlNy00YjBlLWJmNTUtODQyYmI4Yzk4YzAw&redirect_uri=https%3a%2f%2fams--eurcrmlivesg615.crm4.dynamics.com%2f&max_age=86400&claims=%7b%22id_token%22%3a%7b%22xms_cc%22%3a%7b%22values%22%3a%5b%22CP1%22%5d%7d%7d%7d&x-client-SKU=ID_NET472&x-client-ver=8.16.0.0&sso_nonce=AwABEgEAAAADAOz_BQD0_0V2b1N0c0FydGlmYWN0cwUAAAAAAGBHvzpHJPzEb2gRkI35BSN3Oolsbel-kjcmvCgvooWDBhh6GsAlkw1h8sPuyJ9EEnA4ijRbYHwspYyN-XjSFLIgAA&client-request-id=9f20ce7e-48cd-4a6e-b2e9-d578440c518e&mscrid=9f20ce7e-48cd-4a6e-b2e9-d578440c518e" }
            ]
        },
        {
            title: "Securite & Poste de travail",
            icon: "bi-shield-lock",
            color: "#dc3545",
            tools: [
                { name: "Enrollment MFA (Authentification)", host: "10.8.144.109", url: "https://10.8.144.109/mfaapp/auth" },
                { name: "Omnissa Horizon", host: "horizonportal.ecobank.group" },
                { name: "Adobe Acrobat Sign", host: "secure.na3.adobesign.com" }
            ]
        },
        {
            title: "Achats & Administration",
            icon: "bi-file-earmark-text",
            color: "#fd7e14",
            tools: [
                { name: "SAP Ariba — Spend Management", host: "s1.ariba.com" }
            ]
        }
    ];

    function escapeHtml(str) {
        var div = document.createElement("div");
        div.textContent = str == null ? "" : str;
        return div.innerHTML;
    }

    function toolCount(category) {
        return category.tools.length + (category.tools.length > 1 ? " outils" : " outil");
    }

    function render(containerId) {
        var container = document.getElementById(containerId);
        if (!container) return;

        container.innerHTML = CATEGORIES.map(function (cat) {
            var items = cat.tools.map(function (t) {
                var href = t.url || ("https://" + t.host);
                return '<a href="' + href + '" target="_blank" rel="noopener" ' +
                    'class="list-group-item list-group-item-action d-flex justify-content-between align-items-center">' +
                    '<span><i class="bi bi-box-arrow-up-right text-muted me-2"></i>' +
                    '<strong>' + escapeHtml(t.name) + '</strong>' +
                    '<div class="small text-muted ms-4">' + escapeHtml(t.host) + '</div></span>' +
                    '<i class="bi bi-box-arrow-up-right"></i>' +
                    '</a>';
            }).join("");

            return '<div class="col-md-6 col-xl-3">' +
                '<div class="card shadow-sm h-100">' +
                '<div class="card-body">' +
                '<div class="d-flex align-items-center gap-2 mb-2">' +
                '<span class="d-inline-flex align-items-center justify-content-center rounded" ' +
                'style="width:36px;height:36px;background:' + cat.color + '22;color:' + cat.color + ';">' +
                '<i class="bi ' + cat.icon + '"></i></span>' +
                '<div><div class="fw-semibold">' + escapeHtml(cat.title) + '</div>' +
                '<div class="small text-muted">' + toolCount(cat) + '</div></div>' +
                '</div>' +
                '<div class="list-group list-group-flush">' + items + '</div>' +
                '</div></div></div>';
        }).join("");
    }

    return { render: render };
})();
