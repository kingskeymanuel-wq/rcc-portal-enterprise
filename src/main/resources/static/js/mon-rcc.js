"use strict";

(function () {

    var currentProfile = null; // "ADMIN" | "QA" | "AGENT"
    var currentUsername = null;
    var searchDebounceTimer = null;
    var activeConversationId = null;
    var postContentEditor = null;
    var viewedPostIds = {};

    function computeProfile(user) {
        if (user.role && user.role.toUpperCase() === "ADMIN") return "ADMIN";
        if (user.service && user.service.toLowerCase().replace(/_/g, " ") === "quality assurance") return "QA";
        return "AGENT";
    }

    var escapeHtml = RccApi.escapeHtml;

    var getJson = RccApi.getJson;

    var sendJson = function (method, url, body) { return RccApi.sendJson(url, method, body); };

    function formatDate(iso) {
        return new Date(iso).toLocaleString("fr-FR");
    }

    // ===== Onglets =====

    /** Pastille animée sous l'onglet actif. */
    function moveTabInk() {
        var ink = document.querySelector("#monRccTabs .mrcc-tab-ink");
        var active = document.querySelector("#monRccTabs .nav-link.active");
        if (!ink || !active) return;
        ink.style.width = active.offsetWidth + "px";
        ink.style.transform = "translateX(" + active.parentElement.offsetLeft + "px)";
    }

    function wireTabs() {
        var tabs = document.querySelectorAll("#monRccTabs .nav-link");
        setTimeout(moveTabInk, 60);
        window.addEventListener("resize", moveTabInk);
        Array.prototype.forEach.call(tabs, function (tab) {
            tab.addEventListener("click", function () {
                Array.prototype.forEach.call(tabs, function (t) { t.classList.remove("active"); });
                tab.classList.add("active");
                moveTabInk();
                var target = tab.getAttribute("data-tab");
                document.getElementById("feedPane").style.display = target === "feed" ? "" : "none";
                document.getElementById("chatPane").style.display = target === "chat" ? "" : "none";
                document.getElementById("historyPane").style.display = target === "history" ? "" : "none";
                if (target === "chat") {
                    loadDirectory();
                    loadConversations();
                }
                if (target === "history") {
                    loadHistory();
                }
            });
        });
    }

    // ===== Fil d'actualité =====

    function initials(label) {
        var parts = (label || "?").trim().split(/\s+/);
        return (parts[0][0] + (parts[1] ? parts[1][0] : "")).toUpperCase();
    }

    /** Avatar réel si une photo de profil existe (visible partout dans MON RCC), sinon repli sur les initiales. */
    function avatarHtml(photoUrl, label, sizeClass) {
        var cls = "ig-avatar" + (sizeClass ? " " + sizeClass : "");
        if (photoUrl) {
            return '<img class="' + cls + '" src="' + escapeHtml(photoUrl) + '" alt="" ' +
                'style="object-fit:cover;">';
        }
        return '<div class="' + cls + '">' + initials(label) + '</div>';
    }

    function relativeTime(iso) {
        if (!iso) return "";
        var diff = (Date.now() - new Date(iso).getTime()) / 1000;
        if (diff < 60) return "à l'instant";
        if (diff < 3600) return "il y a " + Math.floor(diff / 60) + " min";
        if (diff < 86400) return "il y a " + Math.floor(diff / 3600) + " h";
        if (diff < 7 * 86400) { var d = Math.floor(diff / 86400); return "il y a " + d + " jour" + (d > 1 ? "s" : ""); }
        return new Date(iso).toLocaleDateString("fr-FR", { day: "numeric", month: "long", year: "numeric" });
    }

    function toast(message) {
        var t = document.createElement("div");
        t.className = "mrcc-toast";
        t.textContent = message;
        document.body.appendChild(t);
        setTimeout(function () { t.remove(); }, 2300);
    }

    var hashScrolled = false;
    var SAVED_KEY = "mrcc.savedPosts";
    function savedIds() {
        try { return JSON.parse(localStorage.getItem(SAVED_KEY) || "[]"); } catch (e) { return []; }
    }
    function isSaved(id) { return savedIds().indexOf(String(id)) !== -1; }
    function toggleSaved(id) {
        var ids = savedIds(), i = ids.indexOf(String(id));
        if (i === -1) ids.push(String(id)); else ids.splice(i, 1);
        try { localStorage.setItem(SAVED_KEY, JSON.stringify(ids)); } catch (e) { /* stockage indisponible */ }
        return i === -1;
    }

    /** J'aime instantané (animation), puis synchronisation avec le serveur. */
    function toggleLike(btn) {
        var liked = !btn.classList.contains("liked");
        btn.classList.toggle("liked", liked);
        btn.querySelector("i").className = "bi " + (liked ? "bi-heart-fill" : "bi-heart");
        sendJson("POST", "/api/mon-rcc/posts/" + btn.getAttribute("data-id") + "/like")
            .then(function () { setTimeout(loadPosts, 450); })
            .catch(function (e) { alert("Erreur : " + e.message); loadPosts(); });
    }

    /** Apparition en fondu des publications au défilement. */
    function revealOnScroll(container) {
        var cards = container.querySelectorAll(".mrcc-reveal");
        if (!("IntersectionObserver" in window)) {
            Array.prototype.forEach.call(cards, function (c) { c.classList.add("in"); });
            return;
        }
        var io = new IntersectionObserver(function (entries) {
            entries.forEach(function (e) {
                if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
            });
        }, { threshold: 0.08 });
        Array.prototype.forEach.call(cards, function (c, i) {
            c.style.transitionDelay = Math.min(i, 4) * 70 + "ms";
            io.observe(c);
        });
        // Filet de sécurité : jamais de publication invisible (onglet masqué, impression, capture…).
        setTimeout(function () {
            Array.prototype.forEach.call(cards, function (c) { c.classList.add("in"); });
        }, 1200);
    }

    function countUp(el, value) {
        if (!el) return;
        var start = Number(el.getAttribute("data-v") || 0), t0 = null;
        el.setAttribute("data-v", value);
        function step(ts) {
            if (!t0) t0 = ts;
            var k = Math.min(1, (ts - t0) / 800);
            el.textContent = Math.round(start + (value - start) * (1 - Math.pow(1 - k, 3))).toLocaleString("fr-FR");
            if (k < 1) requestAnimationFrame(step);
        }
        requestAnimationFrame(step);
    }

    /** Chiffres du bandeau + « Tendances » (publications les plus aimées). */
    function updateFeedInsights(posts) {
        var likes = 0, views = 0;
        posts.forEach(function (p) { likes += p.likeCount || 0; views += p.viewCount || 0; });
        countUp(document.getElementById("mrccStatPosts"), posts.length);
        countUp(document.getElementById("mrccStatLikes"), likes);
        countUp(document.getElementById("mrccStatViews"), views);

        var list = document.getElementById("mrccTrendList");
        if (!list) return;
        var top = posts.slice().sort(function (a, b) {
            return ((b.likeCount || 0) * 3 + (b.commentCount || 0) * 2 + (b.viewCount || 0) / 50)
                - ((a.likeCount || 0) * 3 + (a.commentCount || 0) * 2 + (a.viewCount || 0) / 50);
        }).slice(0, 3);
        if (!top.length) { list.innerHTML = '<p class="text-muted small mb-0">Aucune publication pour le moment.</p>'; return; }
        list.innerHTML = top.map(function (p, i) {
            var tmp = document.createElement("div");
            tmp.innerHTML = p.content || "";
            var text = (tmp.textContent || "").trim() || (p.imageUrl ? "Photo / vidéo" : "Publication");
            return '<div class="mrcc-trend" data-id="' + p.id + '" style="animation-delay:' + (i * 80) + 'ms">' +
                '<span class="mrcc-trend-rank">' + (i + 1) + '</span>' +
                '<div class="mrcc-trend-text"><span>' + escapeHtml(text) + '</span>' +
                '<small>' + escapeHtml(p.authorLabel || "") + ' · <i class="bi bi-heart-fill text-danger"></i> ' + (p.likeCount || 0) +
                ' · <i class="bi bi-chat"></i> ' + (p.commentCount || 0) + '</small></div></div>';
        }).join("");
        Array.prototype.forEach.call(list.querySelectorAll(".mrcc-trend"), function (row) {
            row.addEventListener("click", function () {
                var card = document.querySelector('#postsContainer [data-post-id="' + row.getAttribute("data-id") + '"]');
                if (card) card.scrollIntoView({ behavior: "smooth", block: "center" });
            });
        });
    }

    function renderPosts(posts, containerId) {
        var container = document.getElementById(containerId || "postsContainer");
        if (!posts.length) {
            container.innerHTML = '<p class="text-muted text-center">Aucune publication.</p>';
            return;
        }
        container.innerHTML = posts.map(function (p) {
            var image = p.imageUrl ? '<div class="ig-post-image">' + mediaHtml(p.imageUrl, 'style="width:100%;max-height:560px;"') + '</div>' : "";

            var likeIconClass = p.likedByMe ? "bi-heart-fill" : "bi-heart";
            // Modération : QA/Admin peuvent tout gérer, mais l'auteur doit aussi pouvoir
            // modifier/supprimer sa propre publication (déjà autorisé côté backend, il
            // manquait juste le bouton pour un simple agent).
            var canModerateThisPost = currentProfile === "ADMIN" || currentProfile === "QA" ||
                (currentUsername && p.authorMatricule === currentUsername);
            var modButtons = canModerateThisPost ? (
                '<div class="dropdown">' +
                '<button class="btn btn-sm btn-link text-dark p-0" data-bs-toggle="dropdown"><i class="bi bi-three-dots"></i></button>' +
                '<ul class="dropdown-menu dropdown-menu-end">' +
                '<li><button class="dropdown-item edit-post-btn" data-id="' + p.id + '">Modifier</button></li>' +
                '<li><button class="dropdown-item text-danger delete-post-btn" data-id="' + p.id + '">Supprimer</button></li>' +
                '</ul></div>'
            ) : "";

            var likesLine = p.likeCount > 0
                ? '<div class="ig-post-likes">' + p.likeCount + (p.likeCount > 1 ? " mentions J'aime" : " mention J'aime") + '</div>'
                : "";

            var commentsLink = p.commentCount > 0
                ? '<div class="ig-post-comments-link toggle-comments-btn" data-id="' + p.id + '">Afficher les ' + p.commentCount + ' commentaire' + (p.commentCount > 1 ? "s" : "") + '</div>'
                : '<div class="ig-post-comments-link toggle-comments-btn" data-id="' + p.id + '">Ajouter un commentaire...</div>';

            return "" +
                '<div class="ig-post mrcc-reveal' + (p.imageUrl ? "" : " ig-post-textonly") + '" id="post-' + p.id + '" data-post-id="' + p.id + '">' +
                '<div class="ig-post-header">' +
                '<div class="d-flex align-items-center">' +
                avatarHtml(p.authorPhotoUrl, p.authorLabel) +
                '<div><span class="ig-name">' + escapeHtml(p.authorLabel) + '</span>' +
                '<span class="mrcc-post-meta"><i class="bi bi-globe2"></i> ' + relativeTime(p.publishedAt) + '</span></div>' +
                '</div>' + modButtons +
                '</div>' +
                image +
                '<div class="ig-post-actions">' +
                '<button class="like-btn' + (p.likedByMe ? " liked" : "") + '" data-id="' + p.id + '"><i class="bi ' + likeIconClass + '"></i></button>' +
                '<button class="toggle-comments-btn" data-id="' + p.id + '"><i class="bi bi-chat"></i></button>' +
                '<button class="share-btn" data-id="' + p.id + '" title="Copier le lien"><i class="bi bi-send"></i></button>' +
                '<button class="ig-bookmark' + (isSaved(p.id) ? " saved" : "") + '" data-id="' + p.id + '" title="Enregistrer"><i class="bi ' + (isSaved(p.id) ? "bi-bookmark-fill" : "bi-bookmark") + '"></i></button>' +
                '</div>' +
                likesLine +
                '<div class="ig-post-caption"><span class="ig-name">' + escapeHtml(p.authorLabel) + '</span> ' +
                '<span class="post-content">' + p.content + '</span></div>' +
                commentsLink +
                '<div class="ig-post-comments" id="comments-' + p.id + '" style="display:none;"></div>' +
                '<div class="ig-post-time">' + formatDate(p.publishedAt) + ' · <i class="bi bi-eye"></i> ' + p.viewCount + '</div>' +
                '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".like-btn"), function (btn) {
            btn.addEventListener("click", function () { toggleLike(btn); });
        });
        // Double-clic sur la photo : J'aime + grand cœur animé (comme sur les réseaux sociaux).
        Array.prototype.forEach.call(container.querySelectorAll(".ig-post-image"), function (media) {
            media.addEventListener("dblclick", function () {
                var card = media.closest(".ig-post");
                var burst = document.createElement("i");
                burst.className = "bi bi-heart-fill mrcc-burst";
                media.appendChild(burst);
                setTimeout(function () { burst.remove(); }, 900);
                var btn = card.querySelector(".like-btn");
                if (btn && !btn.classList.contains("liked")) toggleLike(btn);
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".share-btn"), function (btn) {
            btn.addEventListener("click", function () {
                var url = location.origin + "/mon-rcc#post-" + btn.getAttribute("data-id");
                (navigator.clipboard ? navigator.clipboard.writeText(url) : Promise.reject())
                    .then(function () { toast("Lien de la publication copié"); })
                    .catch(function () { prompt("Copiez le lien :", url); });
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".ig-bookmark"), function (btn) {
            btn.addEventListener("click", function () {
                var saved = toggleSaved(btn.getAttribute("data-id"));
                btn.classList.toggle("saved", saved);
                btn.querySelector("i").className = "bi " + (saved ? "bi-bookmark-fill" : "bi-bookmark");
                toast(saved ? "Publication enregistrée" : "Retirée des enregistrements");
            });
        });
        revealOnScroll(container);
        if ((containerId || "postsContainer") === "postsContainer") {
            updateFeedInsights(posts);
            if (!hashScrolled && /^#post-\d+$/.test(location.hash)) {
                hashScrolled = true;
                var target = document.getElementById(location.hash.slice(1));
                if (target) setTimeout(function () { target.scrollIntoView({ behavior: "smooth", block: "center" }); }, 300);
            }
        }
        Array.prototype.forEach.call(container.querySelectorAll(".toggle-comments-btn"), function (btn) {
            btn.addEventListener("click", function () { toggleComments(btn.getAttribute("data-id")); });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".delete-post-btn"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer cette publication ?")) return;
                sendJson("DELETE", "/api/mon-rcc/posts/" + btn.getAttribute("data-id"))
                    .then(loadPosts)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".edit-post-btn"), function (btn) {
            btn.addEventListener("click", function () {
                var card = btn.closest(".ig-post");
                var currentText = card.querySelector(".post-content").textContent;
                var updated = prompt("Modifier la publication :", currentText);
                if (updated === null) return;
                sendJson("PUT", "/api/mon-rcc/posts/" + btn.getAttribute("data-id"), { content: updated })
                    .then(loadPosts)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });

        // Enregistre une vue par publication, une seule fois par session de page.
        posts.forEach(function (p) {
            if (!viewedPostIds[p.id]) {
                viewedPostIds[p.id] = true;
                sendJson("POST", "/api/mon-rcc/posts/" + p.id + "/view").catch(function () {});
            }
        });
    }

    function toggleComments(postId) {
        var section = document.getElementById("comments-" + postId);
        if (section.style.display !== "none") {
            section.style.display = "none";
            return;
        }
        section.style.display = "";
        section.innerHTML = '<p class="text-muted small">Chargement…</p>';

        getJson("/api/mon-rcc/posts/" + postId + "/comments").then(function (comments) {
            var list = comments.map(function (c) {
                return '<div class="d-flex align-items-start gap-2 small mb-1">' + avatarHtml(c.authorPhotoUrl, c.authorLabel, "ig-avatar-sm") +
                    '<div><strong>' + escapeHtml(c.authorLabel) + '</strong> : ' +
                    escapeHtml(c.content) + ' <span class="text-muted">(' + formatDate(c.createdAt) + ')</span></div></div>';
            }).join("");

            section.innerHTML = list +
                '<form class="d-flex gap-2 mt-2 comment-form" data-id="' + postId + '">' +
                '<input type="text" class="form-control form-control-sm" placeholder="Ajouter un commentaire..." maxlength="1000">' +
                '<button type="submit" class="btn btn-sm btn-outline-primary">Envoyer</button>' +
                '</form>';

            section.querySelector(".comment-form").addEventListener("submit", function (evt) {
                evt.preventDefault();
                var input = evt.target.querySelector("input");
                var content = input.value.trim();
                if (!content) return;
                sendJson("POST", "/api/mon-rcc/posts/" + postId + "/comments", { content: content })
                    .then(function () {
                        section.style.display = "none";
                        toggleComments(postId);
                        loadPosts();
                    })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    // ===== Communautés (abonnement par service) =====

    var communitiesCache = [];

    function loadCommunities() {
        Promise.all([
            getJson("/api/mon-rcc/communities"),
            getJson("/api/mon-rcc/community-follows/me")
        ]).then(function (results) {
            communitiesCache = results[0];
            var followed = results[1];
            var isAdmin = currentProfile === "ADMIN";

            var PALETTE = ["#0057B8", "#0F9D6C", "#E0435B", "#7B3FE4", "#FF8A00", "#0097A7", "#5C6BC0"];
            var ICONS = { inbound: "bi-telephone-inbound-fill", outbound: "bi-telephone-outbound-fill", reseau: "bi-share-fill",
                social: "bi-share-fill", mail: "bi-envelope-fill", qualit: "bi-award-fill", coach: "bi-award-fill", support: "bi-tools", it: "bi-cpu-fill" };
            function iconFor(label) {
                var l = (label || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
                for (var k in ICONS) if (l.indexOf(k) !== -1) return ICONS[k];
                return "bi-people-fill";
            }
            document.getElementById("communitiesList").innerHTML = communitiesCache.map(function (c, i) {
                var isFollowed = followed.indexOf(c.communityKey) !== -1;
                var adminActions = isAdmin
                    ? '<span class="mrcc-comm-admin"><i class="bi bi-pencil-square edit-community-icon" data-id="' + c.id + '" title="Modifier" style="cursor:pointer;"></i>' +
                      '<i class="bi bi-trash text-danger delete-community-icon" data-id="' + c.id + '" title="Supprimer" style="cursor:pointer;"></i></span>'
                    : "";
                var color = PALETTE[i % PALETTE.length];
                return '<div class="mrcc-comm" style="animation-delay:' + (i * 60) + 'ms">' +
                    '<span class="mrcc-comm-ico" style="background:linear-gradient(135deg,' + color + ',' + color + 'bb)"><i class="bi ' + iconFor(c.label) + '"></i></span>' +
                    '<span class="mrcc-comm-name" title="' + escapeHtml(c.label) + '">' + escapeHtml(c.label) + '</span>' + adminActions +
                    '<button type="button" class="btn btn-sm ' + (isFollowed ? "btn-primary" : "btn-outline-primary") +
                    ' community-btn" data-key="' + c.communityKey + '" data-followed="' + isFollowed + '">' +
                    (isFollowed ? '<i class="bi bi-check-lg"></i> Abonné' : '<i class="bi bi-plus-lg"></i> Suivre') + '</button></div>';
            }).join("") + (isAdmin ? '<button type="button" class="btn btn-sm btn-outline-primary w-100" id="newCommunityBtn"><i class="bi bi-plus-lg"></i> Nouvelle communauté</button>' : "");

            if (isAdmin) {
                document.getElementById("newCommunityBtn").addEventListener("click", function () {
                    var label = prompt("Nom de la nouvelle communauté :");
                    if (!label || !label.trim()) return;
                    sendJson("POST", "/api/mon-rcc/communities", { communityKey: label.trim(), label: label.trim() })
                        .then(loadCommunities).catch(function (e) { alert("Erreur : " + e.message); });
                });
                Array.prototype.forEach.call(document.querySelectorAll(".edit-community-icon"), function (icon) {
                    icon.addEventListener("click", function () {
                        var community = communitiesCache.filter(function (c) { return c.id === Number(icon.dataset.id); })[0];
                        var newLabel = prompt("Nouveau nom :", community.label);
                        if (!newLabel || !newLabel.trim()) return;
                        sendJson("PUT", "/api/mon-rcc/communities/" + community.id, { communityKey: community.communityKey, label: newLabel.trim() })
                            .then(loadCommunities).catch(function (e) { alert("Erreur : " + e.message); });
                    });
                });
                Array.prototype.forEach.call(document.querySelectorAll(".delete-community-icon"), function (icon) {
                    icon.addEventListener("click", function () {
                        if (!confirm("Supprimer cette communauté ? Les abonnements associés seront retirés.")) return;
                        sendJson("DELETE", "/api/mon-rcc/communities/" + icon.dataset.id)
                            .then(loadCommunities).catch(function (e) { alert("Erreur : " + e.message); });
                    });
                });
            }

            Array.prototype.forEach.call(document.querySelectorAll(".community-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    var key = btn.dataset.key;
                    var isFollowed = btn.dataset.followed === "true";
                    var request = isFollowed
                        ? sendJson("DELETE", "/api/mon-rcc/community-follows/" + key)
                        : sendJson("POST", "/api/mon-rcc/community-follows", { communityKey: key });
                    request.then(loadCommunities).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }).catch(function (e) { console.error(e); });
    }

    // ===== Stories =====

    function loadStories() {
        getJson("/api/mon-rcc/stories").then(function (stories) {
            var bar = document.getElementById("storiesBar");
            var addBtn = (currentProfile === "ADMIN" || currentProfile === "QA")
                ? '<div class="text-center flex-shrink-0" style="width:70px;cursor:pointer;" id="addStoryBtn">' +
                  '<div class="mrcc-story-add-ring mx-auto"><i class="bi bi-plus-lg fs-4" style="color:var(--eco-blue);"></i></div>' +
                  '<div class="small mt-1">Ajouter</div></div>'
                : "";

            bar.innerHTML = addBtn + stories.map(function (s) {
                var isVideo = s.imageUrl && isVideoUrl(s.imageUrl);
                var thumb = s.imageUrl
                    ? (isVideo
                        ? '<video src="' + s.imageUrl + '" style="width:100%;height:100%;object-fit:cover;"></video>'
                        : '<img src="' + s.imageUrl + '" style="width:100%;height:100%;object-fit:cover;">')
                    : '<i class="bi bi-person-fill fs-4" style="color:var(--eco-blue);"></i>';
                return '<div class="text-center flex-shrink-0 story-item" data-id="' + s.id + '" style="width:70px;cursor:pointer;">' +
                    '<div class="mrcc-story-ring mx-auto"><div class="mrcc-story-ring-inner">' + thumb + '</div></div>' +
                    '<div class="small mt-1 text-truncate">' + escapeHtml(s.authorLabel) + '</div></div>';
            }).join("");

            if (document.getElementById("addStoryBtn")) {
                document.getElementById("addStoryBtn").addEventListener("click", function () {
                    document.getElementById("storyContentInput").value = "";
                    document.getElementById("storyFileInput").value = "";
                    document.getElementById("storyFilePreviewWrap").style.display = "none";
                    document.getElementById("storyUploadStatus").textContent = "";
                    pendingStoryMediaUrl = null;
                    new bootstrap.Modal(document.getElementById("storyModal")).show();
                });
            }

            Array.prototype.forEach.call(bar.querySelectorAll(".story-item"), function (item) {
                item.addEventListener("click", function () {
                    var index = stories.findIndex(function (s) { return s.id === Number(item.dataset.id); });
                    if (index === -1) return;
                    openStoryViewer(stories, index);
                });
            });
        }).catch(function (e) { console.error(e); });
    }

    // ===== Visualiseur de stories (modale plein écran, défilement précédent/suivant) =====

    var storyViewerList = [];
    var storyViewerIndex = 0;
    var storyViewerTimer = null;

    function ensureStoryViewerModal() {
        if (document.getElementById("storyViewerModal")) return;
        var div = document.createElement("div");
        div.innerHTML =
            '<div class="modal fade" id="storyViewerModal" tabindex="-1">' +
              '<div class="modal-dialog modal-dialog-centered" style="max-width:420px;">' +
                '<div class="modal-content" style="background:#000;border-radius:16px;overflow:hidden;">' +
                  '<div class="d-flex gap-1 p-2" id="storyViewerProgress"></div>' +
                  '<div class="d-flex justify-content-between align-items-center px-3 pb-2">' +
                    '<span class="text-white small fw-semibold" id="storyViewerAuthor"></span>' +
                    '<div>' +
                      '<button type="button" class="btn btn-sm btn-outline-light me-1" id="storyViewerDeleteBtn" style="display:none;"><i class="bi bi-trash"></i></button>' +
                      '<button type="button" class="btn-close btn-close-white" data-bs-dismiss="modal"></button>' +
                    '</div>' +
                  '</div>' +
                  '<div class="position-relative d-flex align-items-center justify-content-center" style="height:65vh;background:#111;">' +
                    '<div id="storyViewerMedia" class="w-100 h-100 d-flex align-items-center justify-content-center"></div>' +
                    '<button type="button" class="btn btn-light rounded-circle position-absolute top-50 start-0 translate-middle-y ms-2" id="storyViewerPrevBtn" style="width:36px;height:36px;padding:0;"><i class="bi bi-chevron-left"></i></button>' +
                    '<button type="button" class="btn btn-light rounded-circle position-absolute top-50 end-0 translate-middle-y me-2" id="storyViewerNextBtn" style="width:36px;height:36px;padding:0;"><i class="bi bi-chevron-right"></i></button>' +
                  '</div>' +
                  '<div class="text-white text-center small p-2" id="storyViewerCaption"></div>' +
                '</div>' +
              '</div>' +
            '</div>';
        document.body.appendChild(div.firstElementChild);

        document.getElementById("storyViewerPrevBtn").addEventListener("click", function () { showStoryAt(storyViewerIndex - 1); });
        document.getElementById("storyViewerNextBtn").addEventListener("click", function () { showStoryAt(storyViewerIndex + 1); });
        document.getElementById("storyViewerDeleteBtn").addEventListener("click", function () {
            var story = storyViewerList[storyViewerIndex];
            if (!story || !confirm("Supprimer cette story ?")) return;
            sendJson("/api/mon-rcc/stories/" + story.id, "DELETE").then(function () {
                bootstrap.Modal.getInstance(document.getElementById("storyViewerModal")).hide();
                loadStories();
            }).catch(function (e) { alert("Erreur : " + e.message); });
        });
        document.getElementById("storyViewerModal").addEventListener("hidden.bs.modal", function () {
            clearTimeout(storyViewerTimer);
            document.getElementById("storyViewerMedia").innerHTML = "";
        });
    }

    function showStoryAt(index) {
        if (index < 0 || index >= storyViewerList.length) {
            bootstrap.Modal.getInstance(document.getElementById("storyViewerModal")).hide();
            return;
        }
        clearTimeout(storyViewerTimer);
        storyViewerIndex = index;
        var story = storyViewerList[index];
        var canDelete = currentProfile === "ADMIN" || currentProfile === "QA";

        document.getElementById("storyViewerAuthor").textContent = story.authorLabel;
        document.getElementById("storyViewerCaption").textContent = story.content || "";
        document.getElementById("storyViewerDeleteBtn").style.display = canDelete ? "" : "none";
        document.getElementById("storyViewerPrevBtn").style.display = index > 0 ? "" : "none";
        document.getElementById("storyViewerNextBtn").style.display = index < storyViewerList.length - 1 ? "" : "none";

        var progressHtml = storyViewerList.map(function (s, i) {
            var fillPct = i < index ? "100" : (i === index ? "0" : "0");
            return '<div style="flex:1;height:3px;border-radius:2px;background:rgba(255,255,255,.35);overflow:hidden;">' +
                '<div class="story-progress-fill" style="height:100%;width:' + fillPct + '%;background:#fff;"></div></div>';
        }).join("");
        document.getElementById("storyViewerProgress").innerHTML = progressHtml;

        var mediaBox = document.getElementById("storyViewerMedia");
        var isVideo = story.imageUrl && isVideoUrl(story.imageUrl);
        if (story.imageUrl) {
            mediaBox.innerHTML = isVideo
                ? '<video src="' + story.imageUrl + '" style="max-width:100%;max-height:100%;" controls autoplay></video>'
                : '<img src="' + story.imageUrl + '" style="max-width:100%;max-height:100%;object-fit:contain;">';
        } else {
            mediaBox.innerHTML = '<div class="text-white text-center px-4">' + escapeHtml(story.content || "") + '</div>';
        }

        // Défilement automatique vers la story suivante après 5s (sauf pour une vidéo, qui gère sa propre durée).
        if (!isVideo) {
            storyViewerTimer = setTimeout(function () { showStoryAt(index + 1); }, 5000);
        }
    }

    function openStoryViewer(stories, startIndex) {
        ensureStoryViewerModal();
        storyViewerList = stories;
        new bootstrap.Modal(document.getElementById("storyViewerModal")).show();
        showStoryAt(startIndex);
    }

    var pendingStoryMediaUrl = null;

    function wireStoryModal() {
        document.getElementById("storyFileInput").addEventListener("change", function () {
            var file = this.files[0];
            if (!file) return;
            var statusBox = document.getElementById("storyUploadStatus");
            uploadMedia(file, statusBox, function (url, video) {
                pendingStoryMediaUrl = url;
                var preview = document.getElementById("storyFilePreview");
                var oldVideo = document.getElementById("storyVideoPreview");
                if (oldVideo) oldVideo.remove();
                if (video) {
                    preview.style.display = "none";
                    preview.insertAdjacentHTML("afterend", '<video id="storyVideoPreview" src="' + escapeHtml(url) + '" controls playsinline style="max-width:100%;max-height:260px;"></video>');
                } else {
                    preview.style.display = "";
                    preview.src = url;
                }
                document.getElementById("storyFilePreviewWrap").style.display = "";
                statusBox.className = "small mt-2 text-success";
                statusBox.textContent = (video ? "Vidéo" : "Photo") + " prête.";
            });
        });

        document.getElementById("publishStoryBtn").addEventListener("click", function () {
            var content = document.getElementById("storyContentInput").value.trim();
            if (!content && !pendingStoryMediaUrl) { alert("Ajoutez un texte ou un fichier."); return; }
            sendJson("POST", "/api/mon-rcc/stories", { content: content, imageUrl: pendingStoryMediaUrl })
                .then(function () {
                    bootstrap.Modal.getInstance(document.getElementById("storyModal")).hide();
                    loadStories();
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    /** Grande bannière en haut de MON RCC — image pilotée depuis Administration (portail IT), même mécanisme que la photo de connexion. */
    function loadMonRccHeroBanner() {
        fetch("/api/site-settings/monrcc-banner", { credentials: "same-origin" })
            .then(function (res) { return res.ok ? res.json() : {}; })
            .then(function (result) {
                if (!result.value) return;
                var banner = document.getElementById("monRccHeroBanner");
                banner.style.backgroundImage = "url('" + result.value + "')";
                banner.style.display = "";
            })
            .catch(function () { /* pas de bannière configurée — reste masquée */ });
    }

    function loadMonRccNews() {
        fetch("/api/news", { credentials: "same-origin" }).then(function (res) {
            return res.ok ? res.json() : [];
        }).then(function (articles) {
            var section = document.getElementById("monRccNewsSection");
            if (!articles.length) { section.innerHTML = ""; section.classList.remove("mrcc-card"); return; }
            section.classList.add("mrcc-card");
            var canManage = currentProfile === "ADMIN" || currentProfile === "QA";
            section.innerHTML = '<div class="mrcc-card-title"><span class="mrcc-ico"><i class="bi bi-newspaper"></i></span> Actualités Ecobank</div>' +
                '<div class="mrcc-news-track">' +
                articles.slice(0, 6).map(function (a, i) {
                    var img = a.imageUrl ? '<div class="mrcc-news-img" style="background-image:url(\'' + a.imageUrl + '\')"></div>' : "";
                    var adminBtns = canManage
                        ? '<div class="d-flex gap-2 mt-2">' +
                          '<button class="btn btn-sm btn-light edit-news-mon-rcc-btn" data-id="' + a.newsId + '"><i class="bi bi-pencil"></i> Modifier</button>' +
                          '<button class="btn btn-sm btn-danger delete-news-mon-rcc-btn" data-id="' + a.newsId + '"><i class="bi bi-trash"></i></button>' +
                          '</div>'
                        : "";
                    return '<div class="mrcc-news-card" style="animation-delay:' + (i * 80) + 'ms">' + img +
                        '<div class="mrcc-news-body"><span class="mrcc-news-tag">Ecobank</span><div class="fw-bold">' + escapeHtml(a.title) + '</div>' + adminBtns + '</div></div>';
                }).join("") + '</div>';

            Array.prototype.forEach.call(section.querySelectorAll(".delete-news-mon-rcc-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    if (!confirm("Supprimer cette actualité ?")) return;
                    fetch("/api/news/" + btn.getAttribute("data-id"), { method: "DELETE", credentials: "same-origin" })
                        .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                        .then(loadMonRccNews)
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
            Array.prototype.forEach.call(section.querySelectorAll(".edit-news-mon-rcc-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    var article = articles.filter(function (a) { return String(a.newsId) === btn.getAttribute("data-id"); })[0];
                    if (article) openNewsEditModal(article);
                });
            });
        }).catch(function () {});
    }

    /** Édition rapide (titre + photo) directement depuis MON RCC — édition complète (contenu riche) toujours possible depuis le tableau de bord. */
    function openNewsEditModal(article) {
        var modalEl = document.getElementById("monRccNewsEditModal");
        if (!modalEl) return;
        document.getElementById("monRccNewsEditTitle").value = article.title;
        document.getElementById("monRccNewsEditId").value = article.newsId;
        var preview = document.getElementById("monRccNewsEditPreview");
        if (article.imageUrl) { preview.src = article.imageUrl; preview.style.display = ""; } else { preview.style.display = "none"; }
        document.getElementById("monRccNewsEditFile").value = "";
        document.getElementById("monRccNewsEditResult").textContent = "";
        new bootstrap.Modal(modalEl).show();
    }

    function wireNewsEditModal() {
        var saveBtn = document.getElementById("monRccNewsEditSaveBtn");
        if (!saveBtn) return;
        saveBtn.addEventListener("click", function () {
            var id = document.getElementById("monRccNewsEditId").value;
            var title = document.getElementById("monRccNewsEditTitle").value.trim();
            var resultBox = document.getElementById("monRccNewsEditResult");
            var file = document.getElementById("monRccNewsEditFile").files[0];
            if (!title) { resultBox.innerHTML = '<span class="text-danger">Le titre est requis.</span>'; return; }

            function save(imageUrl) {
                fetch("/api/news/" + id, {
                    method: "PUT", credentials: "same-origin",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ title: title, imageUrl: imageUrl })
                })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
                    .then(function () {
                        bootstrap.Modal.getInstance(document.getElementById("monRccNewsEditModal")).hide();
                        loadMonRccNews();
                    })
                    .catch(function (e) { resultBox.innerHTML = '<span class="text-danger">Erreur : ' + e.message + '</span>'; });
            }

            if (file) {
                window.RccImageEditor.open(file, function (editedBlob) {
                    var formData = new FormData();
                    formData.append("file", editedBlob, "image.jpg");
                    fetch("/api/news/upload-image", { method: "POST", credentials: "same-origin", body: formData })
                        .then(function (res) { return res.json(); })
                        .then(function (result) { save(result.url); })
                        .catch(function (e) { resultBox.innerHTML = '<span class="text-danger">Erreur upload : ' + e.message + '</span>'; });
                });
            } else {
                var currentImg = document.getElementById("monRccNewsEditPreview").src;
                save(document.getElementById("monRccNewsEditPreview").style.display === "none" ? null : currentImg);
            }
        });
    }

    function loadPosts() {
        getJson("/api/mon-rcc/posts").then(renderPosts).catch(function (e) {
            document.getElementById("postsContainer").innerHTML =
                '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function loadHistory() {
        getJson("/api/mon-rcc/posts/history").then(function (posts) {
            renderPosts(posts, "historyContainer");
        }).catch(function (e) {
            document.getElementById("historyContainer").innerHTML =
                '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    // ===== Envoi de photo / vidéo (fil + stories) =====
    // XMLHttpRequest plutôt que fetch : barre de progression pour les vidéos, et un message
    // clair si le serveur coupe la connexion (au lieu d'un « Failed to fetch » incompréhensible).
    var MAX_VIDEO_MB = 500, MAX_IMAGE_MB = 20;
    var VIDEO_RE = /\.(mp4|m4v|mov|webm)(\?|$)/i;

    function isVideoUrl(url) { return !!url && VIDEO_RE.test(url); }

    function mediaHtml(url, attrs) {
        return isVideoUrl(url)
            ? '<video src="' + escapeHtml(url) + '" controls playsinline preload="metadata" ' + (attrs || "") + '></video>'
            : '<img src="' + escapeHtml(url) + '" alt="" ' + (attrs || "") + '>';
    }

    function uploadMedia(file, statusBox, onDone) {
        var video = /^video\//.test(file.type) || VIDEO_RE.test(file.name);
        var maxMb = video ? MAX_VIDEO_MB : MAX_IMAGE_MB;
        if (file.size > maxMb * 1024 * 1024) {
            statusBox.className = "small mt-1 text-danger";
            statusBox.textContent = (video ? "Vidéo" : "Photo") + " trop volumineuse (" + Math.round(file.size / 1048576) + " Mo, maximum " + maxMb + " Mo).";
            return;
        }
        if (video && !/\.(mp4|m4v|mov|webm)$/i.test(file.name)) {
            statusBox.className = "small mt-1 text-danger";
            statusBox.textContent = "Format vidéo non pris en charge : utilisez MP4, MOV ou WEBM.";
            return;
        }
        var formData = new FormData();
        formData.append("file", file);
        var xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/mon-rcc/media/upload");
        xhr.withCredentials = true;
        statusBox.className = "small mt-1 text-muted";
        statusBox.innerHTML = 'Envoi en cours… <span class="mr-upload-pct">0 %</span><div class="progress mt-1" style="height:6px;max-width:280px;"><div class="progress-bar" style="width:0%"></div></div>';
        xhr.upload.onprogress = function (e) {
            if (!e.lengthComputable) return;
            var pct = Math.round(e.loaded * 100 / e.total);
            var bar = statusBox.querySelector(".progress-bar"), label = statusBox.querySelector(".mr-upload-pct");
            if (bar) bar.style.width = pct + "%";
            if (label) label.textContent = pct + " %" + (pct === 100 ? " — enregistrement…" : "");
        };
        xhr.onload = function () {
            var data = null;
            try { data = JSON.parse(xhr.responseText); } catch (ignore) {}
            if (xhr.status >= 200 && xhr.status < 300 && data && data.url) { onDone(data.url, video); return; }
            statusBox.className = "small mt-1 text-danger";
            statusBox.textContent = "Erreur : " + (data && data.error && data.error.message ? data.error.message
                : xhr.status === 413 ? "fichier trop volumineux pour le serveur." : "envoi refusé (HTTP " + xhr.status + ").");
        };
        xhr.onerror = function () {
            statusBox.className = "small mt-1 text-danger";
            statusBox.textContent = "Envoi interrompu : connexion coupée par le serveur ou le réseau. Si la vidéo est lourde, " +
                "réduisez-la (moins de " + MAX_VIDEO_MB + " Mo) ou contactez l'administrateur (limite du serveur / proxy).";
        };
        xhr.send(formData);
    }

    function showPostPreview(url) {
        var wrap = document.getElementById("postImagePreviewWrap");
        var img = document.getElementById("postImagePreview");
        var old = document.getElementById("postVideoPreview");
        if (old) old.remove();
        if (isVideoUrl(url)) {
            img.style.display = "none";
            img.insertAdjacentHTML("afterend", '<video id="postVideoPreview" src="' + escapeHtml(url) + '" controls playsinline class="rounded" style="max-height:280px;max-width:100%;"></video>');
        } else {
            img.style.display = "";
            img.src = url;
        }
        wrap.style.display = "";
    }

    function wirePostForm() {
        document.getElementById("postImageFileInput").addEventListener("change", function () {
            var file = this.files[0];
            if (!file) return;
            var statusBox = document.getElementById("postImageUploadStatus");
            var input = this;
            uploadMedia(file, statusBox, function (url, video) {
                document.getElementById("postImageInput").value = url;
                showPostPreview(url);
                statusBox.className = "small mt-1 text-success";
                statusBox.textContent = (video ? "Vidéo" : "Photo") + " prête à être publiée.";
                input.value = "";
            });
        });

        document.getElementById("addPostImageBtn").addEventListener("click", function () {
            document.getElementById("postImageInput").style.display = "";
            document.getElementById("postImageInput").focus();
        });

        document.getElementById("schedulePostBtn").addEventListener("click", function () {
            var input = document.getElementById("postScheduleInput");
            var isShown = input.style.display !== "none";
            input.style.display = isShown ? "none" : "";
            document.getElementById("submitPostBtn").textContent = isShown ? "Publier" : "Programmer la publication";
        });

        document.getElementById("postImageInput").addEventListener("input", function () {
            var url = this.value.trim();
            var wrap = document.getElementById("postImagePreviewWrap");
            if (url) {
                showPostPreview(url);
            } else {
                wrap.style.display = "none";
            }
        });

        document.getElementById("removePostImageBtn").addEventListener("click", function () {
            document.getElementById("postImageInput").value = "";
            document.getElementById("postImageInput").style.display = "none";
            document.getElementById("postImagePreviewWrap").style.display = "none";
        });

        document.getElementById("postForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            // Le clic sur "Publier" ne devait jamais rester sans effet visible : tout est
            // maintenant protégé par try/catch, avec une alerte explicite en cas de souci
            // (éditeur pas encore prêt, image trop lourde en URL, etc.) — voir demande
            // utilisateur "on clique sur publier, rien ne se passe".
            var submitBtn = document.getElementById("submitPostBtn");
            var content;
            try {
                content = postContentEditor ? postContentEditor.getHtml().trim() : "";
            } catch (err) {
                alert("Erreur : l'éditeur de texte n'a pas pu être lu. Rechargez la page et réessayez.");
                return;
            }
            var isEmptyContent = !content || content === "<br>" || content === "<div><br></div>";
            var imageUrl = document.getElementById("postImageInput").value.trim() || null;
            // Une publication uniquement composée d'une image (sans légende) est valide :
            // on ne bloque que si NI texte NI image ne sont présents (voir demande
            // utilisateur "on clique sur publier, rien ne se passe" — c'était bloqué ici
            // en silence pour toute publication image seule, sans caption).
            if (isEmptyContent && !imageUrl) {
                alert("Ajoutez du texte ou une image avant de publier.");
                return;
            }
            if (isEmptyContent) {
                content = "";
            }
            if (imageUrl && imageUrl.length > 500) {
                alert("Le lien de l'image est trop long (500 caractères maximum). Utilisez le bouton \"Ajouter une photo\" pour téléverser le fichier au lieu de coller un lien.");
                return;
            }
            var scheduleValue = document.getElementById("postScheduleInput").value;
            var payload = {
                content: content,
                imageUrl: imageUrl,
                visibilityDays: Number(document.getElementById("postVisibilitySelect").value),
                scheduledFor: scheduleValue || null
            };
            submitBtn.disabled = true;
            sendJson("POST", "/api/mon-rcc/posts", payload)
                .then(function (result) {
                    submitBtn.disabled = false;
                    document.getElementById("postForm").reset();
                    if (postContentEditor) postContentEditor.clear();
                    document.getElementById("postImageInput").style.display = "none";
                    document.getElementById("postImagePreviewWrap").style.display = "none";
                    document.getElementById("postImageUploadStatus").textContent = "";
                    document.getElementById("postScheduleInput").style.display = "none";
                    document.getElementById("submitPostBtn").textContent = "Publier";
                    if (result.scheduled) {
                        alert("Publication programmée pour le " + new Date(result.publishedAt).toLocaleString("fr-FR") + ".");
                    }
                    loadPosts();
                })
                .catch(function (e) {
                    submitBtn.disabled = false;
                    alert("Erreur : " + e.message);
                });
        });
    }

    // ===== Messagerie =====

    function loadDirectory() {
        var container = document.getElementById("correspondentResults");
        container.innerHTML = '<p class="text-muted small">Tapez un nom, un identifiant, un service ou une filiale pour rechercher.</p>';
    }

    function groupKey(user) {
        var branch = user.affiliateBranch || "Sans filiale";
        var service = user.service || "Sans service";
        return branch + " — " + service;
    }

    function renderCorrespondents(users) {
        var container = document.getElementById("correspondentResults");
        var groups = {};
        users.forEach(function (u) {
            var key = groupKey(u);
            if (!groups[key]) groups[key] = [];
            groups[key].push(u);
        });

        var keys = Object.keys(groups).sort();
        if (!keys.length) {
            container.innerHTML = '<p class="text-muted small">Aucun correspondant trouvé.</p>';
            return;
        }

        container.innerHTML = keys.map(function (key) {
            var members = groups[key].map(function (u) {
                return '<button class="list-group-item list-group-item-action start-chat-btn" data-username="' +
                    escapeHtml(u.username) + '" data-name="' + escapeHtml(u.fullName) + '" data-email="' + escapeHtml(u.email || "") + '">' +
                    '<div>' + escapeHtml(u.fullName) + '</div>' +
                    '<div class="small text-muted">ID : ' + escapeHtml(u.username) + '</div>' +
                    '</button>';
            }).join("");
            return '<div class="mb-2">' +
                '<div class="small text-muted fw-bold mon-rcc-group-header">' + escapeHtml(key) + '</div>' +
                '<div class="list-group list-group-flush">' + members + '</div>' +
                '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".start-chat-btn"), function (btn) {
            btn.addEventListener("click", function () {
                openChannelChoice(btn.getAttribute("data-username"), btn.getAttribute("data-name"), btn.getAttribute("data-email"));
            });
        });
    }

    /** Choix du canal de contact — envoi réel via Microsoft Graph si configuré, sinon lien classique (mailto:/Teams web) en repli. */
    var graphConfiguredCache = null;
    function isGraphConfigured() {
        if (graphConfiguredCache !== null) return Promise.resolve(graphConfiguredCache);
        return fetch("/api/graph/status", { credentials: "same-origin" })
            .then(function (res) { return res.ok ? res.json() : { configured: false }; })
            .then(function (result) { graphConfiguredCache = !!result.configured; return graphConfiguredCache; })
            .catch(function () { graphConfiguredCache = false; return false; });
    }

    function openChannelChoice(username, name, email) {
        var modalEl = document.getElementById("channelChoiceModal");
        if (!modalEl) { startConversation(username, name); return; } // repli si la modale n'est pas présente sur cette page
        document.getElementById("channelChoiceName").textContent = name;
        var messageBox = document.getElementById("channelChoiceMessage");
        var resultBox = document.getElementById("channelChoiceResult");
        resultBox.textContent = "";
        messageBox.value = "";

        var teamsBtn = document.getElementById("channelChoiceTeamsBtn");
        var outlookBtn = document.getElementById("channelChoiceOutlookBtn");
        teamsBtn.classList.toggle("disabled", !email);
        outlookBtn.classList.toggle("disabled", !email);
        teamsBtn.title = email ? "" : "E-mail non renseigné pour cet agent";
        outlookBtn.title = email ? "" : "E-mail non renseigné pour cet agent";

        isGraphConfigured().then(function (configured) {
            messageBox.classList.toggle("d-none", !configured);

            teamsBtn.onclick = function () {
                if (!email) return;
                if (configured && messageBox.value.trim()) {
                    resultBox.textContent = "Envoi en cours…";
                    sendJson("POST", "/api/graph/send-teams", { toUsername: username, message: messageBox.value.trim() })
                        .then(function () { resultBox.innerHTML = '<span class="text-success">Message Teams envoyé.</span>'; })
                        .catch(function (e) {
                            // Repli automatique — Microsoft refuse parfois l'envoi applicatif Teams selon le tenant.
                            window.open("https://teams.microsoft.com/l/chat/0/0?users=" + encodeURIComponent(email), "_blank");
                            resultBox.innerHTML = '<span class="text-warning">Envoi direct impossible (' + escapeHtml(e.message) + '), Teams ouvert dans un nouvel onglet.</span>';
                        });
                } else {
                    window.open("https://teams.microsoft.com/l/chat/0/0?users=" + encodeURIComponent(email), "_blank");
                    bootstrap.Modal.getInstance(modalEl).hide();
                }
            };
            outlookBtn.onclick = function () {
                if (!email) return;
                if (configured && messageBox.value.trim()) {
                    resultBox.textContent = "Envoi en cours…";
                    sendJson("POST", "/api/graph/send-outlook", { toUsername: username, message: messageBox.value.trim() })
                        .then(function () { resultBox.innerHTML = '<span class="text-success">E-mail envoyé.</span>'; })
                        .catch(function (e) {
                            window.location.href = "mailto:" + encodeURIComponent(email);
                            resultBox.innerHTML = '<span class="text-warning">Envoi direct impossible (' + escapeHtml(e.message) + '), Outlook ouvert.</span>';
                        });
                } else {
                    window.location.href = "mailto:" + encodeURIComponent(email);
                    bootstrap.Modal.getInstance(modalEl).hide();
                }
            };
        });

        document.getElementById("channelChoiceMonRccBtn").onclick = function () {
            bootstrap.Modal.getInstance(modalEl).hide();
            startConversation(username, name);
        };

        new bootstrap.Modal(modalEl).show();
    }

    function searchCorrespondents(query) {
        var container = document.getElementById("correspondentResults");
        query = query.trim();
        if (query.length < 2) {
            container.innerHTML = '<p class="text-muted small">Tapez au moins 2 caractères pour rechercher.</p>';
            return;
        }
        container.innerHTML = '<p class="text-muted small">Recherche…</p>';
        getJson("/api/users/search?q=" + encodeURIComponent(query)).then(function (users) {
            renderCorrespondents(users);
        }).catch(function (e) {
            container.innerHTML = '<p class="text-danger small">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function startConversation(username) {
        sendJson("POST", "/api/chat/conversations", { type: "DM", memberMatricules: [username] })
            .then(function (conversation) {
                loadConversations();
                openConversation(conversation.id, conversation.displayName);
            })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    function renderConversations(conversations) {
        var container = document.getElementById("conversationsList");
        if (!conversations.length) {
            container.innerHTML = '<p class="text-muted small">Aucune conversation.</p>';
            return;
        }
        container.innerHTML = conversations.map(function (c) {
            var unread = c.unreadCount > 0 ? ' <span class="badge bg-danger">' + c.unreadCount + '</span>' : "";
            return '<button class="list-group-item list-group-item-action open-conv-btn" data-id="' + c.id + '" data-name="' +
                escapeHtml(c.displayName) + '">' +
                '<div class="d-flex justify-content-between"><strong>' + escapeHtml(c.displayName) + '</strong>' + unread + '</div>' +
                '<div class="small text-muted text-truncate">' + escapeHtml(c.lastMessage || "") + '</div>' +
                '</button>';
        }).join('<div class="list-group list-group-flush"></div>');
        container.innerHTML = '<div class="list-group list-group-flush">' + container.innerHTML + '</div>';

        Array.prototype.forEach.call(container.querySelectorAll(".open-conv-btn"), function (btn) {
            btn.addEventListener("click", function () {
                openConversation(btn.getAttribute("data-id"), btn.getAttribute("data-name"));
            });
        });
    }

    function loadConversations() {
        getJson("/api/chat/conversations").then(renderConversations).catch(function (e) {
            document.getElementById("conversationsList").innerHTML =
                '<p class="text-danger small">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function renderMessages(messages) {
        var container = document.getElementById("messagesContainer");
        if (!messages.length) {
            container.innerHTML = '<p class="text-muted small text-center">Aucun message pour l\'instant.</p>';
            return;
        }
        container.innerHTML = messages.map(function (m) {
            var cls = m.mine ? "mon-rcc-msg-mine" : "mon-rcc-msg-theirs";
            var isVideo = m.mediaUrl && isVideoUrl(m.mediaUrl);
            var mediaHtml = "";
            if (m.mediaUrl) {
                mediaHtml = isVideo
                    ? '<video src="' + escapeHtml(m.mediaUrl) + '" controls style="max-width:220px;border-radius:.5rem;" class="d-block mb-1"></video>'
                    : '<img src="' + escapeHtml(m.mediaUrl) + '" alt="" style="max-width:220px;border-radius:.5rem;" class="d-block mb-1">';
            }
            var ephemeralBadge = m.expiresAt
                ? '<span class="badge bg-warning text-dark ms-1" title="Message éphémère"><i class="bi bi-stopwatch"></i></span>'
                : "";
            var textHtml = m.content ? escapeHtml(m.content) : "";
            var isSticker = m.content && !m.mediaUrl && STICKER_LIST.indexOf(m.content.trim()) !== -1;
            var bubbleHtml = isSticker
                ? '<div class="mon-rcc-msg-bubble" style="background:transparent;box-shadow:none;font-size:2.6rem;padding:.2rem;">' + textHtml + '</div>'
                : '<div class="mon-rcc-msg-bubble">' + mediaHtml + textHtml + '</div>';
            var deleteBtn = m.mine
                ? '<button class="btn btn-sm btn-link text-muted p-0 delete-message-btn" data-id="' + m.id + '" title="Supprimer ce message"><i class="bi bi-trash"></i></button>'
                : "";
            var avatar = m.mine ? "" : avatarHtml(m.senderPhotoUrl, m.senderName, "ig-avatar-sm");
            return '<div class="' + cls + ' mb-2 d-flex align-items-end gap-2' + (m.mine ? " justify-content-end" : "") + '">' +
                avatar +
                '<div>' + bubbleHtml +
                '<div class="small text-muted d-flex align-items-center gap-1' + (m.mine ? " justify-content-end" : "") + '">' +
                formatDate(m.sentAt) + ephemeralBadge + deleteBtn + '</div></div>' +
                '</div>';
        }).join("");
        container.scrollTop = container.scrollHeight;

        Array.prototype.forEach.call(container.querySelectorAll(".delete-message-btn"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer ce message ?")) return;
                fetch("/api/chat/messages/" + btn.getAttribute("data-id"), { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                    .then(function () { return getJson("/api/chat/conversations/" + activeConversationId + "/messages"); })
                    .then(renderMessages)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    function openConversation(conversationId, displayName) {
        activeConversationId = conversationId;
        document.getElementById("activeConversationTitle").textContent = displayName || "Conversation";
        document.getElementById("messageForm").style.display = "flex";

        getJson("/api/chat/conversations/" + conversationId + "/messages")
            .then(function (messages) {
                renderMessages(messages);
                return sendJson("POST", "/api/chat/conversations/" + conversationId + "/read");
            })
            .then(loadConversations)
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    /** Fond d'écran personnel des conversations — chargé une fois à l'ouverture de MON RCC. */
    function applyChatBackground(url) {
        var container = document.getElementById("messagesContainer");
        if (!container) return;
        if (url) {
            container.style.backgroundImage = "url('" + url + "')";
            container.style.backgroundSize = "cover";
            container.style.backgroundPosition = "center";
        } else {
            container.style.backgroundImage = "";
        }
    }

    var EMOJI_LIST = ["😀","😂","😍","😊","🙂","😉","😎","🤔","😢","😭","😡","😱","👍","👎","👏","🙏","💪","🤝","❤️",
        "🔥","🎉","✅","❌","⚠️","⏰","📞","📧","💡","🚀","🙌","😴","🤯","🥳","🫡","👀","💯","🤗","😅","🤝"];

    var STICKER_LIST = ["😀","😂","😍","🥳","😎","🙌","👍","❤️","🔥","🎉","💪","🤝","👏","🙏","💯","🚀","✅","😴","🤗","😇"];

    /** Insère un caractère à la position du curseur dans #messageInput — évite d'écraser ce que l'agent tapait déjà. */
    function insertAtCursor(input, text) {
        var start = input.selectionStart || input.value.length;
        var end = input.selectionEnd || input.value.length;
        input.value = input.value.slice(0, start) + text + input.value.slice(end);
        var pos = start + text.length;
        input.setSelectionRange(pos, pos);
        input.focus();
    }

    function wireEmojiPicker() {
        var toggleBtn = document.getElementById("emojiToggleBtn");
        var picker = document.getElementById("emojiPicker");
        if (!toggleBtn || !picker) return;

        document.getElementById("emojiGrid").innerHTML = EMOJI_LIST.map(function (e) {
            return '<button type="button" class="btn btn-sm emoji-btn" style="font-size:1.2rem;">' + e + '</button>';
        }).join("");
        document.getElementById("stickerGrid").innerHTML = STICKER_LIST.map(function (e) {
            return '<button type="button" class="btn btn-sm sticker-btn" style="font-size:2rem;">' + e + '</button>';
        }).join("");

        toggleBtn.addEventListener("click", function (evt) {
            evt.stopPropagation();
            picker.classList.toggle("d-none");
        });
        document.addEventListener("click", function () { picker.classList.add("d-none"); });
        picker.addEventListener("click", function (evt) { evt.stopPropagation(); });

        Array.prototype.forEach.call(picker.querySelectorAll(".emoji-btn"), function (btn) {
            btn.addEventListener("click", function () {
                insertAtCursor(document.getElementById("messageInput"), btn.textContent);
            });
        });

        // Un sticker s'envoie directement, comme un vrai sticker de messagerie — pas juste inséré dans le texte.
        Array.prototype.forEach.call(picker.querySelectorAll(".sticker-btn"), function (btn) {
            btn.addEventListener("click", function () {
                if (!activeConversationId) return;
                picker.classList.add("d-none");
                sendJson("POST", "/api/chat/conversations/" + activeConversationId + "/messages",
                    { content: btn.textContent, mediaUrl: null, ephemeralMinutes: null })
                    .then(function () { return getJson("/api/chat/conversations/" + activeConversationId + "/messages"); })
                    .then(renderMessages)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    function loadChatBackground() {
        getJson("/api/user-profiles/me").then(function (profile) {
            applyChatBackground(profile.chatBackgroundUrl);
        }).catch(function () { /* pas de profil — fond par défaut */ });
    }

    function wireChatBackgroundControls() {
        var input = document.getElementById("chatBackgroundInput");
        var resetBtn = document.getElementById("resetChatBackgroundBtn");
        if (!input) return;

        input.addEventListener("change", function () {
            var file = this.files[0];
            if (!file) return;
            var formData = new FormData();
            formData.append("file", file);
            fetch("/api/user-profiles/me/chat-background", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function (profile) { applyChatBackground(profile.chatBackgroundUrl); input.value = ""; })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });

        if (resetBtn) {
            resetBtn.addEventListener("click", function () {
                fetch("/api/user-profiles/me/chat-background", { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
                    .then(function (profile) { applyChatBackground(profile.chatBackgroundUrl); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        }
    }

    var pendingChatMediaUrl = null;

    function wireMessageForm() {
        document.getElementById("chatMediaInput").addEventListener("change", function () {
            var file = this.files[0];
            if (!file) return;
            var formData = new FormData();
            formData.append("file", file);
            fetch("/api/mon-rcc/media/upload", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function (result) {
                    pendingChatMediaUrl = result.url;
                    document.getElementById("chatMediaPreview").src = result.url;
                    document.getElementById("chatMediaPreviewWrap").style.display = "";
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });

        document.getElementById("removeChatMediaBtn").addEventListener("click", function () {
            pendingChatMediaUrl = null;
            document.getElementById("chatMediaInput").value = "";
            document.getElementById("chatMediaPreviewWrap").style.display = "none";
        });

        document.getElementById("ephemeralToggleBtn").addEventListener("click", function () {
            var select = document.getElementById("ephemeralDurationSelect");
            var isOn = this.classList.toggle("btn-warning");
            this.classList.toggle("btn-outline-secondary", !isOn);
            select.style.display = isOn ? "" : "none";
        });

        document.getElementById("messageForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            if (!activeConversationId) return;
            var input = document.getElementById("messageInput");
            var content = input.value.trim();
            if (!content && !pendingChatMediaUrl) return;

            var isEphemeral = document.getElementById("ephemeralToggleBtn").classList.contains("btn-warning");
            var payload = {
                content: content,
                mediaUrl: pendingChatMediaUrl,
                ephemeralMinutes: isEphemeral ? Number(document.getElementById("ephemeralDurationSelect").value) : null
            };

            sendJson("POST", "/api/chat/conversations/" + activeConversationId + "/messages", payload)
                .then(function () {
                    input.value = "";
                    pendingChatMediaUrl = null;
                    document.getElementById("chatMediaInput").value = "";
                    document.getElementById("chatMediaPreviewWrap").style.display = "none";
                    return getJson("/api/chat/conversations/" + activeConversationId + "/messages");
                })
                .then(renderMessages)
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    function wireCorrespondentSearch() {
        document.getElementById("correspondentSearch").addEventListener("input", function () {
            var value = this.value;
            clearTimeout(searchDebounceTimer);
            searchDebounceTimer = setTimeout(function () { searchCorrespondents(value); }, 300);
        });
    }

    var WALLPAPER_STORAGE_KEY = "mrcc-wallpaper";
    var WALLPAPER_CLASSES = ["mrcc-wp-solid-blue", "mrcc-wp-solid-cream", "mrcc-wp-dots", "mrcc-wp-diagonal", "mrcc-wp-dark"];

    function applyWallpaper(className) {
        var container = document.getElementById("messagesContainer");
        WALLPAPER_CLASSES.forEach(function (c) { container.classList.remove(c); });
        container.classList.add(className);
        Array.prototype.forEach.call(document.querySelectorAll(".mrcc-wallpaper-swatch"), function (swatch) {
            swatch.classList.toggle("active", swatch.getAttribute("data-wp") === className);
        });
        try { localStorage.setItem(WALLPAPER_STORAGE_KEY, className); } catch (e) { /* navigation privée : tant pis, pas bloquant */ }
    }

    function wireWallpaperPicker() {
        var saved = null;
        try { saved = localStorage.getItem(WALLPAPER_STORAGE_KEY); } catch (e) { /* ignore */ }
        applyWallpaper(saved && WALLPAPER_CLASSES.indexOf(saved) !== -1 ? saved : "mrcc-wp-solid-blue");

        document.getElementById("wallpaperToggleBtn").addEventListener("click", function (evt) {
            evt.stopPropagation();
            document.getElementById("wallpaperPicker").classList.toggle("d-none");
        });
        document.addEventListener("click", function () {
            document.getElementById("wallpaperPicker").classList.add("d-none");
        });
        Array.prototype.forEach.call(document.querySelectorAll(".mrcc-wallpaper-swatch"), function (swatch) {
            swatch.addEventListener("click", function (evt) {
                evt.stopPropagation();
                applyWallpaper(swatch.getAttribute("data-wp"));
            });
        });
    }

    function init() {
        wireTabs();
        wirePostForm();
        wireMessageForm();
        wireCorrespondentSearch();
        wireStoryModal();
        wireWallpaperPicker();
        wireChatBackgroundControls();
        wireEmojiPicker();
        wireNewsEditModal();
        loadChatBackground();
        loadMonRccHeroBanner();
        loadMonRccNews();
        loadPosts();

        fetch("/api/auth/me", { credentials: "same-origin" })
            .then(function (res) { return res.json(); })
            .then(function (user) {
                currentProfile = computeProfile(user);
                currentUsername = user.username;
                // Publication réservée à QA/Admin — l'agent conseiller ne peut plus publier (retiré à sa demande explicite).
                if (currentProfile === "ADMIN" || currentProfile === "QA") {
                    document.getElementById("createPostCard").style.display = "";
                    document.getElementById("composerAvatar").textContent = initials(user.name || user.username);
                    postContentEditor = window.RccRichText.create(document.getElementById("postContentEditor"), "");
                }
                loadPosts();
                loadStories();
                loadCommunities();
                loadMonRccNews(); // relance après connaître le rôle — les boutons Modifier/Supprimer n'apparaissent qu'à ce moment-là

                // Rafraîchissement périodique des stories : sans ça, une story publiée par
                // QA/Admin après le chargement initial de la page n'apparaît jamais pour un
                // agent qui a MON RCC déjà ouvert (il faudrait recharger la page à la main).
                // En pause si l'onglet est en arrière-plan (économie de requêtes) ou pendant
                // la saisie d'une nouvelle story (storyModal ouvert), pour ne pas faire
                // disparaître son contenu de brouillon sous ses yeux.
                var STORIES_REFRESH_INTERVAL_MS = 45000;
                setInterval(function () {
                    if (document.hidden) return;
                    var modalEl = document.getElementById("storyModal");
                    if (modalEl && modalEl.classList.contains("show")) return;
                    loadStories();
                }, STORIES_REFRESH_INTERVAL_MS);
            })
            .catch(function (e) { console.error("mon-rcc.js init failed:", e); });
    }

    document.addEventListener("DOMContentLoaded", init);
})();
