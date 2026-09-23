/**
 * Éditeur de texte riche léger — pas de dépendance externe, réutilisable partout
 * où le contenu est stocké en HTML côté serveur (Knowledge Base, publications MON RCC).
 *
 * Usage :
 *   var editor = RccRichText.create(document.getElementById("monConteneur"), "<p>contenu initial</p>");
 *   editor.getHtml();       // récupère le contenu actuel
 *   editor.setHtml(html);   // remplace le contenu
 *   editor.clear();
 */
(function () {
    "use strict";

    var FONT_FAMILIES = [
        { label: "Police par défaut", value: "" },
        { label: "Arial", value: "Arial, sans-serif" },
        { label: "Georgia", value: "Georgia, serif" },
        { label: "Courier New", value: "'Courier New', monospace" },
        { label: "Verdana", value: "Verdana, sans-serif" }
    ];

    var FONT_SIZES = [
        { label: "Petit", value: "2" },
        { label: "Normal", value: "3" },
        { label: "Moyen", value: "4" },
        { label: "Grand", value: "5" },
        { label: "Très grand", value: "6" }
    ];

    function el(tag, attrs, html) {
        var e = document.createElement(tag);
        if (attrs) Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
        if (html !== undefined) e.innerHTML = html;
        return e;
    }

    function create(container, initialHtml) {
        container.innerHTML = "";
        container.classList.add("rcc-richtext-wrap");

        var toolbar = el("div", { class: "rcc-richtext-toolbar" });

        function toolBtn(icon, title, command, value) {
            var btn = el("button", { type: "button", class: "btn btn-sm btn-outline-secondary", title: title });
            btn.innerHTML = '<i class="bi ' + icon + '"></i>';
            btn.addEventListener("mousedown", function (evt) {
                evt.preventDefault(); // garde le focus/la sélection dans l'éditeur
                document.execCommand(command, false, value || null);
            });
            return btn;
        }

        toolbar.appendChild(toolBtn("bi-type-bold", "Gras", "bold"));
        toolbar.appendChild(toolBtn("bi-type-italic", "Italique", "italic"));
        toolbar.appendChild(toolBtn("bi-type-underline", "Souligné", "underline"));

        var fontSelect = el("select", { class: "form-select form-select-sm rcc-richtext-select", title: "Police" });
        FONT_FAMILIES.forEach(function (f) {
            var opt = el("option", { value: f.value }, f.label);
            fontSelect.appendChild(opt);
        });
        fontSelect.addEventListener("mousedown", function (evt) { evt.stopPropagation(); });
        fontSelect.addEventListener("change", function () {
            document.execCommand("fontName", false, fontSelect.value || "inherit");
        });
        toolbar.appendChild(fontSelect);

        var sizeSelect = el("select", { class: "form-select form-select-sm rcc-richtext-select", title: "Taille" });
        FONT_SIZES.forEach(function (s) {
            var opt = el("option", { value: s.value }, s.label);
            if (s.value === "3") opt.selected = true;
            sizeSelect.appendChild(opt);
        });
        sizeSelect.addEventListener("mousedown", function (evt) { evt.stopPropagation(); });
        sizeSelect.addEventListener("change", function () {
            document.execCommand("fontSize", false, sizeSelect.value);
        });
        toolbar.appendChild(sizeSelect);

        var colorInput = el("input", { type: "color", class: "form-control form-control-color rcc-richtext-color", title: "Couleur du texte", value: "#1a1a1a" });
        colorInput.addEventListener("input", function () {
            document.execCommand("foreColor", false, colorInput.value);
        });
        toolbar.appendChild(colorInput);

        toolbar.appendChild(toolBtn("bi-list-ul", "Liste à puces", "insertUnorderedList"));
        toolbar.appendChild(toolBtn("bi-list-ol", "Liste numérotée", "insertOrderedList"));
        toolbar.appendChild(toolBtn("bi-eraser", "Effacer la mise en forme", "removeFormat"));

        var editable = el("div", {
            class: "rcc-richtext-editable form-control",
            contenteditable: "true"
        }, initialHtml || "");

        // Nettoyage du contenu collé (Ctrl+V) : évite que du HTML pollué venant d'un
        // autre outil (attributs data-sourcepos, classes utilitaires propres à la
        // source, balises <script>/<style>...) ne se retrouve figé dans l'éditeur —
        // et donc, plus tard, affiché tel quel (balises visibles en texte) au lieu
        // d'être interprété comme mise en forme. On garde volontairement la mise en
        // forme utile (gras, italique, listes, titres, tableaux, liens) et on jette
        // le reste.
        editable.addEventListener("paste", function (evt) {
            evt.preventDefault();

            var clipboard = evt.clipboardData || window.clipboardData;
            if (!clipboard) return;

            var html = clipboard.getData("text/html");
            var cleaned;

            if (html) {
                cleaned = sanitizePastedHtml(html);
            } else {
                var text = clipboard.getData("text/plain") || "";
                cleaned = escapeForInsertion(text).replace(/\r?\n/g, "<br>");
            }

            document.execCommand("insertHTML", false, cleaned);
        });

        container.appendChild(toolbar);
        container.appendChild(editable);

        return {
            getHtml: function () { return editable.innerHTML; },
            setHtml: function (html) { editable.innerHTML = html || ""; },
            clear: function () { editable.innerHTML = ""; },
            focus: function () { editable.focus(); }
        };
    }

    /**
     * Balises conservées lors du collage — tout le reste est déballé (le contenu
     * texte/enfant est gardé, la balise elle-même est retirée) ou, pour les balises
     * dangereuses/inutiles (script, style, meta, link...), supprimé entièrement.
     */
    var ALLOWED_TAGS = {
        P: true, DIV: true, SPAN: true, BR: true, HR: true,
        H1: true, H2: true, H3: true, H4: true, H5: true, H6: true,
        UL: true, OL: true, LI: true,
        STRONG: true, B: true, EM: true, I: true, U: true, S: true, MARK: true,
        A: true, IMG: true,
        TABLE: true, THEAD: true, TBODY: true, TR: true, TH: true, TD: true,
        BLOCKQUOTE: true, CODE: true, PRE: true
    };

    /** Attributs conservés, par balise (tout autre attribut — data-*, class, id... — est retiré). */
    var ALLOWED_ATTRS = {
        A: { href: true, target: true, rel: true },
        IMG: { src: true, alt: true, width: true, height: true },
        TD: { colspan: true, rowspan: true },
        TH: { colspan: true, rowspan: true }
    };

    /** Balises dont le contenu doit être supprimé entièrement (pas seulement déballé). */
    var STRIP_ENTIRELY = { SCRIPT: true, STYLE: true, META: true, LINK: true, HEAD: true, TITLE: true, IFRAME: true, OBJECT: true, EMBED: true };

    function sanitizePastedHtml(rawHtml) {
        var parser = document.createElement("div");
        parser.innerHTML = rawHtml;

        cleanNode(parser);

        return parser.innerHTML;
    }

    function cleanNode(root) {
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT, null);
        var toUnwrap = [];
        var toRemove = [];

        var node = walker.nextNode();
        while (node) {
            var tag = node.tagName;

            if (STRIP_ENTIRELY[tag]) {
                toRemove.push(node);
            } else if (!ALLOWED_TAGS[tag]) {
                toUnwrap.push(node);
            } else {
                // Retire tous les attributs sauf la liste blanche pour cette balise
                // (élimine data-sourcepos, class, style, id, dir, et tout attribut
                // spécifique à l'outil source).
                var keep = ALLOWED_ATTRS[tag] || {};
                Array.prototype.slice.call(node.attributes).forEach(function (attr) {
                    if (!keep[attr.name.toLowerCase()]) node.removeAttribute(attr.name);
                });
                if (tag === "A" && node.getAttribute("href")) {
                    node.setAttribute("rel", "noopener noreferrer");
                }
            }

            node = walker.nextNode();
        }

        toRemove.forEach(function (n) { if (n.parentNode) n.parentNode.removeChild(n); });
        toUnwrap.forEach(function (n) {
            if (!n.parentNode) return;
            while (n.firstChild) n.parentNode.insertBefore(n.firstChild, n);
            n.parentNode.removeChild(n);
        });
    }

    function escapeForInsertion(s) {
        var div = document.createElement("div");
        div.textContent = s == null ? "" : String(s);
        return div.innerHTML;
    }

    window.RccRichText = { create: create };
})();
