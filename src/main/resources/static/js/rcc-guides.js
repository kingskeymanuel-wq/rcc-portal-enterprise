"use strict";

/**
 * Pas à pas animés (« motion spot ») — lecteur et bibliothèque, à partir de window.RCC_GUIDES (guides-data.js).
 *
 * RccGuide.open(ref)              ouvre le lecteur dans une fenêtre (ref = "ecobank-mobile/enrolement" ou "enrolement")
 * RccGuide.mountLibrary(el)       bibliothèque par plateforme (base de connaissance → onglet « Pas à pas »)
 * RccGuide.list()                 [{ ref, title, platform }] — pour lier un formulaire de campagne à un pas à pas
 *
 * Le lecteur montre l'écran du téléphone, un doigt animé vers la zone à toucher, les transitions d'écran, ce qu'il
 * faut dire au client, les conseils et points de vigilance. Aux embranchements, l'agent clique la situation du
 * client et le lecteur déroule le bon processus. Lecture automatique (pause aux embranchements), clavier ← →.
 */
window.RccGuide = (function () {
    function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
    function data() { return window.RCC_GUIDES || { platforms: [] }; }

    function find(ref) {
        if (!ref) return null;
        var parts = String(ref).split("/"), pid = parts.length > 1 ? parts[0] : null, gid = parts[parts.length - 1];
        var out = null;
        data().platforms.forEach(function (p) {
            if (pid && p.id !== pid) return;
            p.guides.forEach(function (g) { if (!out && g.id === gid) out = { platform: p, guide: g }; });
        });
        return out;
    }

    function list() {
        var out = [];
        data().platforms.forEach(function (p) { p.guides.forEach(function (g) { out.push({ ref: p.id + "/" + g.id, title: g.title, platform: p.name, category: g.category }); }); });
        return out;
    }

    // ───────────── Écran reconstitué (aspect de l'application) ─────────────

    function mockHtml(m) {
        var rows = (m.rows || []).map(function (r, i) {
            var hot = r.hot ? " hot" : "";
            switch (r.t) {
                case "balance": return '<div class="gm-balance"><small>' + esc(r.label) + '</small><b>' + esc(r.value) + '</b></div>';
                case "option": return '<div class="gm-option' + hot + '" data-row="' + i + '"><i class="bi ' + esc(r.icon || "bi-chevron-right") + '"></i><span>' + esc(r.label) + (r.sub ? '<small>' + esc(r.sub) + '</small>' : '') + '</span><i class="bi bi-chevron-right gm-chev"></i></div>';
                case "select": return '<div class="gm-field' + hot + '" data-row="' + i + '"><small>' + esc(r.label) + '</small><span>' + esc(r.value || " ") + '<i class="bi bi-chevron-down"></i></span></div>';
                case "field": return '<div class="gm-field' + hot + '" data-row="' + i + '"><small>' + esc(r.label) + '</small><span>' + esc(r.value || " ") + '</span></div>';
                case "button": return '<div class="gm-button' + hot + '" data-row="' + i + '">' + esc(r.label) + '</div>';
                case "info": return '<div class="gm-info"><i class="bi bi-info-circle"></i> ' + esc(r.text) + '</div>';
                case "success": return '<div class="gm-success"><i class="bi bi-check-circle-fill"></i><b>' + esc(r.text) + '</b></div>';
                case "summary": return '<div class="gm-summary">' + r.items.map(function (x) { return '<div><small>' + esc(x[0]) + '</small><b>' + esc(x[1]) + '</b></div>'; }).join("") + '</div>';
                case "scan": return '<div class="gm-scan"><div></div><i class="bi bi-qr-code"></i></div>';
                case "card": return '<div class="gm-card"><b>VISA</b><span>•••• •••• •••• 4821</span><small>Carte virtuelle</small></div>';
                default: return "";
            }
        }).join("");
        return '<div class="gm"><div class="gm-top"><i class="bi bi-arrow-left"></i><span>' + esc(m.title || "") + '</span></div><div class="gm-body">' + rows + '</div></div>';
    }

    /** Ligne d'un écran reconstitué qui correspond le mieux à un choix (mots en commun). */
    function rowForChoice(step, label) {
        if (!step.mock) return -1;
        var words = String(label).toLowerCase().split(/[^a-zà-ÿ0-9]+/).filter(function (w) { return w.length > 2; });
        var best = -1, score = 0;
        step.mock.rows.forEach(function (r, i) {
            var l = String(r.label || "").toLowerCase(), s = 0;
            words.forEach(function (w) { if (l.indexOf(w) !== -1) s++; });
            if (s > score) { score = s; best = i; }
        });
        return best;
    }

    // ───────────── Lecteur ─────────────

    function player(root, found, opts) {
        opts = opts || {};
        var g = found.guide, p = found.platform;
        var path = [g.start], dir = 1, auto = false, timer = null;
        function cur() { return g.steps[path[path.length - 1]]; }
        function countLinear() {
            // Longueur estimée du parcours restant (premier choix à chaque embranchement) pour la barre de progression.
            var n = 0, id = path[path.length - 1], seen = {};
            while (id && g.steps[id] && !seen[id]) { seen[id] = 1; n++; var s = g.steps[id]; id = s.next || (s.choices ? s.choices[0].next : null); }
            return path.length - 1 + n;
        }

        root.innerHTML = '<div class="gp">' +
            '<div class="gp-stage"><div class="gp-phone"><div class="gp-notch"></div><div class="gp-screen"><div class="gp-finger"><i class="bi bi-hand-index-thumb-fill"></i></div><div class="gp-ring"></div></div></div></div>' +
            '<div class="gp-side">' +
            '<div class="gp-head"><span class="gp-plat" style="background:' + esc(p.color) + '"><i class="bi ' + esc(p.icon) + '"></i> ' + esc(p.name) + '</span>' +
            (g.source === "guide" ? '<span class="gp-src ok"><i class="bi bi-patch-check-fill"></i> Guide officiel</span>' : '<span class="gp-src" title="Reconstitué d\'après l\'application — à valider par l\'équipe Digital"><i class="bi bi-magic"></i> Pas à pas indicatif</span>') +
            '<h5>' + esc(g.title) + '</h5><div class="gp-bar"><div></div></div></div>' +
            '<div class="gp-step"></div>' +
            '<div class="gp-ctrl"><button type="button" class="btn btn-light" data-g="prev"><i class="bi bi-arrow-left"></i> Précédent</button>' +
            '<button type="button" class="btn btn-outline-primary" data-g="auto"><i class="bi bi-play-fill"></i> Lecture auto</button>' +
            '<button type="button" class="btn btn-light" data-g="restart" title="Recommencer"><i class="bi bi-arrow-counterclockwise"></i></button>' +
            '<button type="button" class="btn btn-light" data-g="copy" title="Copier les étapes à envoyer au client (SMS, WhatsApp)"><i class="bi bi-clipboard"></i></button>' +
            '<button type="button" class="btn btn-primary ms-auto" data-g="next">Suivant <i class="bi bi-arrow-right"></i></button></div>' +
            '</div></div>';

        var screen = root.querySelector(".gp-screen"), finger = root.querySelector(".gp-finger"), ring = root.querySelector(".gp-ring");

        function pointAt(x, y) {
            finger.classList.remove("go"); ring.classList.remove("go");
            void finger.offsetWidth;
            finger.style.left = x + "%"; finger.style.top = y + "%";
            ring.style.left = x + "%"; ring.style.top = y + "%";
            finger.classList.add("go"); ring.classList.add("go");
        }

        function pointAtRow(i) {
            var el = screen.querySelector('[data-row="' + i + '"]');
            if (!el) return;
            var sr = screen.getBoundingClientRect(), r = el.getBoundingClientRect();
            pointAt((r.left + r.width / 2 - sr.left) * 100 / sr.width, (r.top + r.height / 2 - sr.top) * 100 / sr.height);
        }

        function aimDefault(s) {
            if (s.mock) {
                var hot = screen.querySelector(".hot");
                if (hot) pointAtRow(hot.getAttribute("data-row"));
                else { finger.classList.remove("go"); ring.classList.remove("go"); }
            } else if (s.tap) pointAt(s.tap[0], s.tap[1]);
            else { finger.classList.remove("go"); ring.classList.remove("go"); }
        }

        function render() {
            var s = cur(), n = path.length, total = Math.max(n, countLinear());
            var layer = document.createElement("div");
            layer.className = "gp-layer in-" + (dir > 0 ? "right" : "left");
            layer.innerHTML = s.img ? '<img src="' + esc(s.img) + '" alt="' + esc(s.title) + '">' : mockHtml(s.mock || {});
            Array.prototype.forEach.call(screen.querySelectorAll(".gp-layer"), function (old) { old.classList.add("out"); setTimeout(function () { old.remove(); }, 450); });
            screen.appendChild(layer);
            var img = layer.querySelector("img");
            var aim = function () { setTimeout(function () { aimDefault(s); }, 420); };
            if (img && !img.complete) img.addEventListener("load", aim); else aim();

            root.querySelector(".gp-bar > div").style.width = Math.round(n * 100 / total) + "%";
            root.querySelector(".gp-step").innerHTML =
                '<div class="gp-n">Étape ' + n + (s.done ? ' · terminé' : '') + '</div><h6>' + esc(s.title) + '</h6>' +
                (s.say ? '<div class="gp-say"><i class="bi bi-chat-quote-fill"></i><div><small>À dire au client</small>' + esc(s.say) + '</div></div>' : '') +
                (s.tip ? '<div class="gp-tip"><i class="bi bi-lightbulb-fill"></i> ' + esc(s.tip) + '</div>' : '') +
                (s.warn ? '<div class="gp-warn"><i class="bi bi-shield-exclamation"></i> ' + esc(s.warn) + '</div>' : '') +
                (s.choices ? '<div class="gp-q">Selon la situation du client :</div><div class="gp-choices">' + s.choices.map(function (c, i) {
                    return '<button type="button" data-choice="' + i + '"><b>' + esc(c.label) + '</b>' + (c.hint ? '<small>' + esc(c.hint) + '</small>' : '') + '<i class="bi bi-arrow-right-circle-fill"></i></button>';
                }).join("") + '</div>' : '') +
                (s.done ? '<div class="gp-done"><i class="bi bi-check-circle-fill"></i> Accompagnement terminé</div>' + related() : '');
            root.querySelector('[data-g="prev"]').disabled = n === 1;
            var nextBtn = root.querySelector('[data-g="next"]');
            nextBtn.disabled = !s.next;
            nextBtn.style.visibility = s.choices || s.done ? "hidden" : "visible";
            if (auto) schedule();
            if (opts.onStep) opts.onStep(path.slice());
        }

        function related() {
            var others = p.guides.filter(function (x) { return x.id !== g.id; }).slice(0, 4);
            return others.length ? '<div class="gp-rel"><small>Continuer avec :</small>' + others.map(function (x) {
                return '<button type="button" data-open="' + esc(p.id + "/" + x.id) + '"><i class="bi ' + esc(x.icon) + '"></i> ' + esc(x.title) + '</button>';
            }).join("") + '</div>' : '';
        }

        function go(id) { if (!g.steps[id]) return; dir = 1; path.push(id); render(); }
        function back() { if (path.length > 1) { dir = -1; path.pop(); render(); } }
        function schedule() {
            clearTimeout(timer);
            var s = cur();
            if (!auto || !s.next) { if (auto && (s.choices || s.done)) setAuto(false); return; }
            timer = setTimeout(function () { go(s.next); }, 6500);
        }
        function setAuto(on) {
            auto = on;
            var b = root.querySelector('[data-g="auto"]');
            b.innerHTML = on ? '<i class="bi bi-pause-fill"></i> Pause' : '<i class="bi bi-play-fill"></i> Lecture auto';
            b.classList.toggle("active", on);
            if (on) schedule(); else clearTimeout(timer);
        }

        function copyText() {
            var lines = [g.title + " — " + p.name], i = 1;
            path.forEach(function (id) { var s = g.steps[id]; if (s.say) lines.push(i++ + ". " + s.say); });
            var s = cur();
            var id = s.next;
            while (id && g.steps[id]) { if (g.steps[id].say) lines.push(i++ + ". " + g.steps[id].say); id = g.steps[id].next; }
            var text = lines.join("\n");
            var done = function () { var b = root.querySelector('[data-g="copy"]'); b.innerHTML = '<i class="bi bi-check2"></i>'; setTimeout(function () { b.innerHTML = '<i class="bi bi-clipboard"></i>'; }, 1500); };
            if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, done); else done();
        }

        root.addEventListener("click", function (e) {
            var b = e.target.closest("button");
            if (!b || !root.contains(b)) return;
            if (b.dataset.choice != null) { var c = cur().choices[Number(b.dataset.choice)]; setAuto(false); go(c.next); return; }
            if (b.dataset.open) { if (opts.onOpen) opts.onOpen(b.dataset.open); return; }
            switch (b.dataset.g) {
                case "next": setAuto(false); if (cur().next) go(cur().next); break;
                case "prev": setAuto(false); back(); break;
                case "auto": setAuto(!auto); break;
                case "restart": setAuto(false); dir = -1; path = [g.start]; render(); break;
                case "copy": copyText(); break;
            }
        });
        // Survol d'un choix : le doigt montre où le client doit appuyer.
        root.addEventListener("mouseover", function (e) {
            var b = e.target.closest("[data-choice]");
            if (!b) return;
            var s = cur(), c = s.choices ? s.choices[Number(b.dataset.choice)] : null;
            if (!c) return;
            if (s.mock) { var i = rowForChoice(s, c.label); if (i >= 0) pointAtRow(i); }
            else if (c.tap) pointAt(c.tap[0], c.tap[1]);
        });
        function onKey(e) {
            if (!document.body.contains(root)) { document.removeEventListener("keydown", onKey); return; }
            if (/INPUT|TEXTAREA|SELECT/.test(e.target.tagName)) return;
            if (e.key === "ArrowRight" && cur().next) { setAuto(false); go(cur().next); }
            if (e.key === "ArrowLeft") { setAuto(false); back(); }
        }
        document.addEventListener("keydown", onKey);
        render();
        return { stop: function () { setAuto(false); document.removeEventListener("keydown", onKey); } };
    }

    // ───────────── Fenêtre ─────────────

    var modalEl = null, current = null;
    function open(ref) {
        var found = find(ref);
        if (!found) { alert("Pas à pas introuvable : " + ref); return; }
        if (!modalEl) {
            modalEl = document.createElement("div");
            modalEl.className = "modal fade";
            modalEl.tabIndex = -1;
            modalEl.innerHTML = '<div class="modal-dialog modal-xl modal-dialog-centered"><div class="modal-content gp-modal"><div class="modal-header py-2"><h6 class="modal-title"><i class="bi bi-collection-play-fill"></i> Pas à pas</h6>' +
                '<button type="button" class="btn-close" data-bs-dismiss="modal"></button></div><div class="modal-body"></div></div></div>';
            document.body.appendChild(modalEl);
            modalEl.addEventListener("hidden.bs.modal", function () { if (current) current.stop(); });
        }
        if (current) current.stop();
        current = player(modalEl.querySelector(".modal-body"), found, { onOpen: open });
        bootstrap.Modal.getOrCreateInstance(modalEl).show();
    }

    // ───────────── Bibliothèque (base de connaissance) ─────────────

    function mountLibrary(root) {
        var st = { platform: "ecobank-mobile", q: "", cat: "" };
        function draw() {
            var plats = data().platforms, p = plats.filter(function (x) { return x.id === st.platform; })[0] || plats[0];
            var cats = [];
            p.guides.forEach(function (g) { if (cats.indexOf(g.category) === -1) cats.push(g.category); });
            var q = st.q.toLowerCase();
            var guides = p.guides.filter(function (g) {
                return (!st.cat || g.category === st.cat) && (!q || (g.title + " " + g.summary + " " + Object.keys(g.steps).map(function (k) { return g.steps[k].say || ""; }).join(" ")).toLowerCase().indexOf(q) !== -1);
            });
            root.innerHTML = '<div class="gl">' +
                '<div class="gl-plats">' + plats.map(function (x) {
                    return '<button type="button" class="' + (x.id === p.id ? "on" : "") + (x.ready ? "" : " soon") + '" data-plat="' + esc(x.id) + '"' + (x.ready ? "" : " disabled") + '>' +
                        '<i class="bi ' + esc(x.icon) + '" style="color:' + esc(x.color) + '"></i><span><b>' + esc(x.name) + '</b><small>' + (x.ready ? x.guides.length + " pas à pas" : "Bientôt") + '</small></span></button>';
                }).join("") + '</div>' +
                '<div class="gl-intro"><div><h5><i class="bi ' + esc(p.icon) + '"></i> ' + esc(p.name) + '</h5><p>' + esc(p.description) + '</p></div>' +
                '<input type="search" class="form-control" data-gl-q placeholder="Rechercher : virement, facture, code PIN…" value="' + esc(st.q) + '"></div>' +
                '<div class="gl-cats"><button type="button" data-cat="" class="' + (!st.cat ? "on" : "") + '">Tout</button>' + cats.map(function (c) { return '<button type="button" data-cat="' + esc(c) + '" class="' + (st.cat === c ? "on" : "") + '">' + esc(c) + '</button>'; }).join("") + '</div>' +
                '<div class="gl-grid">' + (guides.length ? guides.map(function (g) {
                    return '<button type="button" class="gl-card" data-open="' + esc(p.id + "/" + g.id) + '"><span class="gl-ico" style="background:' + esc(p.color) + '"><i class="bi ' + esc(g.icon) + '"></i></span>' +
                        '<b>' + esc(g.title) + '</b><small>' + esc(g.summary) + '</small><span class="gl-meta"><span>' + Object.keys(g.steps).length + ' écrans</span><span>' + esc(g.duration || "") + '</span>' +
                        (g.source === "guide" ? '<span class="ok"><i class="bi bi-patch-check-fill"></i> Officiel</span>' : '<span>Indicatif</span>') + '</span><span class="gl-play"><i class="bi bi-play-circle-fill"></i> Lancer</span></button>';
                }).join("") : '<p class="text-muted">Aucun pas à pas ne correspond.</p>') + '</div></div>';
        }
        root.addEventListener("click", function (e) {
            var b = e.target.closest("button");
            if (!b) return;
            if (b.dataset.plat) { st.platform = b.dataset.plat; st.cat = ""; draw(); }
            else if (b.dataset.cat != null) { st.cat = b.dataset.cat; draw(); }
            else if (b.dataset.open) open(b.dataset.open);
        });
        root.addEventListener("input", function (e) {
            if (!e.target.hasAttribute("data-gl-q")) return;
            st.q = e.target.value;
            var pos = e.target.selectionStart;
            draw();
            var inp = root.querySelector("[data-gl-q]"); inp.focus(); inp.setSelectionRange(pos, pos);
        });
        draw();
    }

    return { open: open, find: find, list: list, mountLibrary: mountLibrary, player: player };
})();
