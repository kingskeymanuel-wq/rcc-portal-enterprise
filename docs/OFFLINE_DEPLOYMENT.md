# RCC Portal — fonctionnement sur un serveur SANS accès Internet

Depuis la v182, toutes les ressources de l'interface sont servies par le portail lui-même
(`src/main/resources/static/vendor`) : aucune page ne dépend plus d'un CDN public.

| Ressource | Copie locale |
|---|---|
| Bootstrap 5.3.3 (CSS + JS) | `/vendor/bootstrap/` |
| Bootstrap Icons 1.11.3 (+ polices) | `/vendor/bootstrap-icons/` |
| Chart.js 4.4.4 | `/vendor/chartjs/` |
| Flatpickr 4.6.13 | `/vendor/flatpickr/` |
| Leaflet (carte) | `/vendor/leaflet/` |
| Fond de carte vectoriel (frontières des pays) + topojson-client | `/vendor/world/` |
| Drapeaux des filiales (SVG, flag-icons) | `/vendor/flags/` |
| Polices | `/vendor/fonts/` |
| Couvertures de campagnes | `/images/covers/` |

## Ce qui fonctionne sans Internet
Tout le portail : connexion (passerelle SAGED interne), tableaux de bord, procédures, cartographie
des cas, base de connaissances, masques de mail, MON RCC, formation, QA, RH, planning, Portail Agence
(disponibilité cartes / GAB, boîte à outils), RAF (agents locaux), carte des agences (fond vectoriel local).

## Ce qui nécessite un accès externe (dégradé proprement sans Internet)
| Fonction | Sans Internet | Pour l'avoir quand même |
|---|---|---|
| Fond de carte détaillé (rues) | fond vectoriel local (pays + marqueurs) | serveur de tuiles interne → `RCC_MAP_TILE_URL` (vide = fond local seul) |
| Visite de rue / plan / itinéraire (fiche agence) | fiche locale : adresse + coordonnées GPS à copier | — |
| Recherche web de RAF / barre de recherche | ignorée (moteur écarté 2 min après un échec, aucune attente) | SearXNG interne → `WEBSEARCH_SEARXNG_URL` |
| Traduction | LibreTranslate local (recommandé) + glossaire hors ligne | kit `scripts/libretranslate-offline` (installation sans Internet) |
| Géocodage d'adresse (admin agences) | message + saisie lat/long ou clic sur la carte | — |
| Teams / Outlook (Graph), Copilot, DeepL, Azure, Anthropic | désactivés tant que non configurés | clés dans `application-secrets.yml` |
| Vidéos YouTube / Vimeo de formation | non lues | déposer les vidéos dans le portail (upload local) |

Pour couper explicitement tout appel externe : `WEBSEARCH_ENABLED=false` et
`RCC_TRANSLATION_MYMEMORY_ENABLED=false`.
