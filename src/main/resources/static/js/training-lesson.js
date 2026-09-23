(function () {
    'use strict';

    const lessonId = window.__LESSON_ID__;
    const MAX_SAFE_RATE_MARGIN = 0.01;

    let lesson = null;
    let maxAllowedRate = 1.5;
    let lastSentScroll = 0;
    let lastSentVideo = 0;
    let sendTimer = null;
    let videoEl = null;

    document.addEventListener('DOMContentLoaded', function () {
        if (window.RccSession) window.RccSession.init();
        init();
    });

    async function init() {
        if (!lessonId || isNaN(lessonId)) {
            document.getElementById('lessonContentHtml').innerHTML = '<p class="text-danger">Leçon introuvable.</p>';
            return;
        }
        try {
            lesson = await RccApi.getJson(`/api/training/lessons/${lessonId}`);
        } catch (e) {
            document.getElementById('lessonContentHtml').innerHTML = '<p class="text-danger">Impossible de charger la leçon.</p>';
            return;
        }

        maxAllowedRate = lesson.videoMaxPlaybackRate || 1.5;

        document.getElementById('lessonTitle').textContent = lesson.title || 'Leçon';
        document.getElementById('lessonContentHtml').innerHTML = lesson.contentHtml || '<p class="text-muted">Aucun contenu texte pour cette leçon.</p>';
        document.getElementById('lessonMaxRateBadge').innerHTML =
            `<i class="bi bi-shield-lock"></i> Vitesse vidéo max : ${maxAllowedRate}x`;

        setupNavButtons();
        updateProgressUi(lesson.scrollPercent || 0, lesson.videoWatchedPercent || 0, !!lesson.completed);

        if (lesson.videoUrl) {
            setupVideo(lesson.videoUrl);
        }

        setupScrollTracking();
    }

    function setupNavButtons() {
        const prevBtn = document.getElementById('btnPrevLesson');
        const nextBtn = document.getElementById('btnNextLesson');
        if (lesson.prevLessonId) {
            prevBtn.disabled = false;
            prevBtn.onclick = () => window.location.href = `/training/lesson/${lesson.prevLessonId}`;
        }
        if (lesson.nextLessonId) {
            nextBtn.disabled = false;
            nextBtn.onclick = () => window.location.href = `/training/lesson/${lesson.nextLessonId}`;
        } else {
            nextBtn.textContent = 'Retour à la formation';
            nextBtn.disabled = false;
            nextBtn.onclick = () => window.location.href = '/training';
        }
    }

    // ===================== SCROLL TRACKING =====================
    // Le pourcentage est calculé à partir de la position réelle de scroll dans la
    // zone de contenu (pas seulement "a ouvert la leçon") : impossible d'obtenir
    // 100% sans avoir réellement fait défiler jusqu'au bas du texte.
    function setupScrollTracking() {
        const scrollArea = document.getElementById('lessonScrollArea');
        if (!scrollArea) return;

        const computeAndReport = () => {
            const scrollTop = scrollArea.scrollTop;
            const scrollHeight = scrollArea.scrollHeight - scrollArea.clientHeight;
            let percent;
            if (scrollHeight <= 0) {
                // Contenu tient entièrement dans la zone visible : considéré comme lu
                // seulement après un court délai de lecture (anti-triche "ouvre et ferme").
                percent = 100;
            } else {
                percent = Math.round((scrollTop / scrollHeight) * 100);
            }
            percent = Math.max(0, Math.min(100, percent));
            if (percent > lastSentScroll) {
                lastSentScroll = percent;
                scheduleSend();
            }
            updateProgressUi(lastSentScroll, lastSentVideo, lesson.completed);
        };

        scrollArea.addEventListener('scroll', throttle(computeAndReport, 400));
        // Si le contenu est court (pas de vrai scroll possible), on exige un délai
        // minimal de lecture avant d'accorder les 100% de scroll.
        const scrollHeight = scrollArea.scrollHeight - scrollArea.clientHeight;
        if (scrollHeight <= 0) {
            setTimeout(computeAndReport, 8000);
        }
    }

    // ===================== VERROUILLAGE VITESSE VIDÉO =====================
    function setupVideo(videoUrl) {
        const card = document.getElementById('lessonVideoCard');
        card.style.display = '';
        videoEl = document.getElementById('lessonVideo');
        videoEl.src = videoUrl;
        videoEl.defaultPlaybackRate = 1.0;
        videoEl.playbackRate = 1.0;

        // Empêche toute tentative (menu natif, devtools, extension) de dépasser 1.5x :
        // si le navigateur/l'utilisateur force une vitesse supérieure, on la ramène
        // immédiatement à la vitesse max autorisée.
        videoEl.addEventListener('ratechange', () => {
            if (videoEl.playbackRate > maxAllowedRate + MAX_SAFE_RATE_MARGIN) {
                videoEl.playbackRate = maxAllowedRate;
                showSpeedLockToast();
            }
        });

        videoEl.addEventListener('timeupdate', throttle(() => {
            if (!videoEl.duration || isNaN(videoEl.duration)) return;
            const percent = Math.round((videoEl.currentTime / videoEl.duration) * 100);
            if (percent > lastSentVideo) {
                lastSentVideo = Math.min(100, percent);
                scheduleSend();
            }
            document.getElementById('videoWatchedLabel').textContent = lastSentVideo + '%';
            updateProgressUi(lastSentScroll, lastSentVideo, lesson.completed);
        }, 1000));

        // Empêche le "seek" massif vers la fin pour se déclarer "vu" sans regarder :
        // un saut de plus de 15s est ramené juste après le point déjà validé.
        let lastKnownTime = 0;
        videoEl.addEventListener('timeupdate', () => { lastKnownTime = videoEl.currentTime; });
        videoEl.addEventListener('seeking', () => {
            const maxReachedTime = (lastSentVideo / 100) * (videoEl.duration || 0);
            if (videoEl.currentTime > maxReachedTime + 15) {
                videoEl.currentTime = maxReachedTime;
            }
        });
    }

    function showSpeedLockToast() {
        let toast = document.getElementById('speedLockToast');
        if (!toast) {
            toast = document.createElement('div');
            toast.id = 'speedLockToast';
            toast.className = 'position-fixed bottom-0 end-0 m-3 alert alert-warning shadow lesson-locked-badge';
            toast.style.zIndex = 2000;
            toast.innerHTML = '<i class="bi bi-shield-lock"></i> Vitesse limitée à ' + maxAllowedRate + 'x pour cette formation.';
            document.body.appendChild(toast);
        }
        toast.style.display = 'block';
        clearTimeout(toast._hideTimer);
        toast._hideTimer = setTimeout(() => { toast.style.display = 'none'; }, 3000);
    }

    // ===================== ENVOI AU SERVEUR (throttlé) =====================
    function scheduleSend() {
        if (sendTimer) return;
        sendTimer = setTimeout(async () => {
            sendTimer = null;
            try {
                const rate = videoEl ? videoEl.playbackRate : null;
                const result = await RccApi.sendJson(`/api/training/lessons/${lessonId}/progress`, 'POST', {
                    scrollPercent: lastSentScroll,
                    videoWatchedPercent: lastSentVideo,
                    reportedPlaybackRate: rate
                });
                lesson.completed = result.completed;
                updateProgressUi(result.scrollPercent, result.videoWatchedPercent, result.completed);
            } catch (e) { /* silencieux — nouvel envoi au prochain scroll/tick */ }
        }, 1500);
    }

    function updateProgressUi(scrollPercent, videoPercent, completed) {
        const hasVideo = !!(lesson && lesson.videoUrl);
        const overall = hasVideo ? Math.round((scrollPercent + videoPercent) / 2) : scrollPercent;
        document.getElementById('lessonProgressFill').style.width = overall + '%';
        document.getElementById('lessonPercentBadge').textContent = overall + '%';
        const banner = document.getElementById('lessonCompleteBanner');
        if (completed) {
            banner.classList.add('show');
        }
    }

    function throttle(fn, delayMs) {
        let last = 0;
        return function (...args) {
            const now = Date.now();
            if (now - last >= delayMs) {
                last = now;
                fn.apply(this, args);
            }
        };
    }
})();
