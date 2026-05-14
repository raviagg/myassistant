import math
import os
from dataclasses import dataclass, field
from datetime import datetime


# ── Normalisation caps ────────────────────────────────────────────────────────
ARTICLE_CAP         = int(os.environ.get("NEWS_ARTICLE_CAP", "200"))
SOCIAL_CAP          = float(os.environ.get("NEWS_SOCIAL_CAP", "10000"))
CATEGORY_WEIGHT_CAP = float(os.environ.get("NEWS_CATEGORY_WEIGHT_CAP", "100"))

# ── Phase-1 scoring weights (per-category ranking) ────────────────────────────
P1_ARTICLE_WEIGHT   = float(os.environ.get("NEWS_P1_ARTICLE_WEIGHT", "0.50"))
P1_SOCIAL_WEIGHT    = float(os.environ.get("NEWS_P1_SOCIAL_WEIGHT", "0.30"))
P1_CATEGORY_WEIGHT  = float(os.environ.get("NEWS_P1_CATEGORY_WEIGHT", "0.20"))

# ── Phase-2 scoring weights (cross-category ranking, no filtering) ────────────
P2_ARTICLE_WEIGHT   = float(os.environ.get("NEWS_P2_ARTICLE_WEIGHT", "0.60"))
P2_SOCIAL_WEIGHT    = float(os.environ.get("NEWS_P2_SOCIAL_WEIGHT", "0.40"))

# ── Result limits ─────────────────────────────────────────────────────────────
TOP_PER_CATEGORY    = int(os.environ.get("NEWS_TOP_PER_CATEGORY", "3"))
# Total events selected after Phase-2; budget is distributed evenly across categories.
# e.g. MAX_EVENTS=10, 4 categories → 3+3+2+2 (higher-ranked categories get the extra slot).
MAX_EVENTS          = int(os.environ.get("NEWS_MAX_EVENTS", "20"))
ARTICLES_PER_EVENT  = int(os.environ.get("NEWS_ARTICLES_PER_EVENT", "3"))
# Max events to fetch per category across all pages (API returns 50 per page)
MAX_FETCH_PER_CATEGORY = int(os.environ.get("NEWS_MAX_FETCH_PER_CATEGORY", "200"))

# ── Logging ───────────────────────────────────────────────────────────────────
# Max events logged with rank detail per category (0 = disabled)
LOG_PER_CATEGORY    = int(os.environ.get("NEWS_LOG_PER_CATEGORY", "15"))


@dataclass
class NewsArticle:
    headline: str
    url: str
    published_date: str
    source: str | None
    description: str | None


@dataclass
class NewsEvent:
    event_uri: str
    title: str
    summary: str | None
    category: str | None
    article_count: int | None
    social_score: float | None
    published_date: str
    articles: list[NewsArticle] = field(default_factory=list)


def _get_text(obj, lang: str = "eng") -> str | None:
    if isinstance(obj, str):
        return obj or None
    if isinstance(obj, dict):
        return obj.get(lang) or next((v for v in obj.values() if v), None)
    return None


def _score(article_count: int, social_score: float, phase: int, category_weight: float = 0.0) -> float:
    article_norm  = math.log(1 + min(article_count, ARTICLE_CAP)) / math.log(1 + ARTICLE_CAP)
    social_norm   = min(social_score, SOCIAL_CAP) / SOCIAL_CAP
    if phase == 1:
        category_norm = min(category_weight, CATEGORY_WEIGHT_CAP) / CATEGORY_WEIGHT_CAP
        return P1_ARTICLE_WEIGHT * article_norm + P1_SOCIAL_WEIGHT * social_norm + P1_CATEGORY_WEIGHT * category_norm
    return P2_ARTICLE_WEIGHT * article_norm + P2_SOCIAL_WEIGHT * social_norm


def _select_with_category_budget(
    phase2: "list[tuple[NewsEvent, float]]", max_events: int
) -> "list[NewsEvent]":
    """Distribute max_events slots evenly across categories.

    Categories whose top event ranks highest in Phase-2 get the extra slot.
    e.g. max_events=10, 4 categories → budgets are 3, 3, 2, 2.
    Within each category the highest Phase-2 ranked events are taken first.
    """
    if not phase2:
        return []

    # Unique categories in Phase-2 rank order (first appearance = highest-ranked top event)
    seen_cats: list[str] = []
    for evt, _ in phase2:
        cat = evt.category or ""
        if cat not in seen_cats:
            seen_cats.append(cat)

    num_cats = len(seen_cats)
    base  = max_events // num_cats
    extra = max_events % num_cats
    cat_budget = {cat: base + (1 if i < extra else 0) for i, cat in enumerate(seen_cats)}

    budget_str = " + ".join(str(cat_budget[c]) for c in seen_cats)
    print(f"[news_poll] Phase-2 selection: max_events={max_events}, "
          f"{num_cats} categories, budget={budget_str}")

    cat_used: dict[str, int] = {}
    selected: list[NewsEvent] = []
    for evt, _ in phase2:
        cat = evt.category or ""
        used = cat_used.get(cat, 0)
        if used < cat_budget.get(cat, base):
            selected.append(evt)
            cat_used[cat] = used + 1
        if len(selected) >= max_events:
            break

    return selected


def _fetch_all_pages(er, kwargs: dict, ReturnInfo, RequestEventsInfo, EventInfoFlags) -> list[dict]:
    """Fetch all pages of events for a category, up to MAX_FETCH_PER_CATEGORY."""
    all_events: list[dict] = []
    page = 1
    while len(all_events) < MAX_FETCH_PER_CATEGORY:
        from eventregistry import QueryEvents
        q = QueryEvents(**kwargs)
        q.setRequestedResult(RequestEventsInfo(
            page=page, count=50,
            sortBy="date", sortByAsc=False,
            returnInfo=ReturnInfo(eventInfo=EventInfoFlags(socialScore=True)),
        ))
        res = er.execQuery(q)
        events_data = res.get("events", {})
        results = events_data.get("results", [])
        all_events.extend(results)
        total_available = events_data.get("totalResults", 0)
        if len(results) < 50 or len(all_events) >= total_available:
            break
        page += 1
    return all_events[:MAX_FETCH_PER_CATEGORY]


def fetch_ranked_events(
    categories: list[str],
    sources: list[str],
    date_start: datetime,
    date_end: datetime,
) -> list[NewsEvent]:
    from eventregistry import (
        EventRegistry, RequestEventsInfo, ReturnInfo,
        EventInfoFlags, QueryEvent, RequestEventArticles, QueryItems,
    )

    api_key = os.environ["NEWSAPIAI_KEY"]
    er = EventRegistry(apiKey=api_key, allowUseOfArchive=False)

    date_start_str = date_start.strftime("%Y-%m-%d")
    date_end_str = date_end.strftime("%Y-%m-%d")

    per_category_top: list[tuple[NewsEvent, float]] = []

    for category_uri in categories:
        try:
            kwargs: dict = {
                "categoryUri": category_uri,
                "dateStart": date_start_str,
                "dateEnd": date_end_str,
                "lang": "eng",
            }
            if sources:
                kwargs["sourceUri"] = QueryItems.OR(sources)

            raw_events = _fetch_all_pages(er, kwargs, ReturnInfo, RequestEventsInfo, EventInfoFlags)

            scored: list[tuple[dict, float]] = []
            for ev in raw_events:
                art_count = int(ev.get("totalArticleCount", 0) or 0)
                soc_score = float(ev.get("socialScore", 0) or 0)
                cat_weight = next(
                    (float(c.get("wgt", 0)) for c in ev.get("categories", []) if c.get("uri") == category_uri),
                    0.0,
                )
                scored.append((ev, _score(art_count, soc_score, phase=1, category_weight=cat_weight)))

            scored.sort(key=lambda x: x[1], reverse=True)

            # ── Phase-1 rank log (fixed per-category budget) ──────────────────
            log_n = min(len(scored), LOG_PER_CATEGORY) if LOG_PER_CATEGORY > 0 else 0
            print(f"[news_poll] Phase-1 category={category_uri!r}: "
                  f"{len(raw_events)} events fetched, logging top {log_n}, keeping top {TOP_PER_CATEGORY}")
            for rank, (ev, s) in enumerate(scored[:log_n], start=1):
                art  = int(ev.get("totalArticleCount", 0) or 0)
                soc  = float(ev.get("socialScore", 0) or 0)
                cwgt = next((float(c.get("wgt", 0)) for c in ev.get("categories", [])
                             if c.get("uri") == category_uri), 0.0)
                kept = "✓" if rank <= TOP_PER_CATEGORY else " "
                title = (_get_text(ev.get("title")) or "")[:60]
                print(f"[news_poll]   {kept} #{rank:>3}  score={s:.4f}  "
                      f"art={art:>4}  soc={soc:>8.0f}  cat_wgt={cwgt:>5.0f}  {title!r}")

            for ev, s in scored[:TOP_PER_CATEGORY]:
                evt = NewsEvent(
                    event_uri=ev["uri"],
                    title=_get_text(ev.get("title")) or "",
                    summary=_get_text(ev.get("summary")),
                    category=category_uri,
                    article_count=ev.get("totalArticleCount"),
                    social_score=ev.get("socialScore"),
                    published_date=ev.get("eventDate", ""),
                    articles=[],
                )
                per_category_top.append((evt, s))

        except Exception as e:
            print(f"[news_poll] error fetching category {category_uri!r}: {e}")

    # Phase-2: cross-category re-score and rank — no filtering, caller decides how many to store
    seen_uris: set[str] = set()
    phase2: list[tuple[NewsEvent, float]] = []
    for evt, _ in per_category_top:
        if evt.event_uri in seen_uris:
            continue
        seen_uris.add(evt.event_uri)
        art_count = int(evt.article_count or 0)
        soc_score = float(evt.social_score or 0)
        phase2.append((evt, _score(art_count, soc_score, phase=2)))

    phase2.sort(key=lambda x: x[1], reverse=True)

    # ── Phase-2 rank log (all candidates before budget selection) ───────────
    print(f"[news_poll] Phase-2 cross-category: {len(phase2)} unique events")
    for rank, (evt, s) in enumerate(phase2, start=1):
        art   = int(evt.article_count or 0)
        soc   = float(evt.social_score or 0)
        cat   = (evt.category or "")[-30:]
        title = evt.title[:55]
        print(f"[news_poll]   #{rank:>3}  score={s:.4f}  "
              f"art={art:>4}  soc={soc:>8.0f}  cat=...{cat}  {title!r}")

    ranked_events = _select_with_category_budget(phase2, MAX_EVENTS)

    # Fetch top articles for each selected event, filtered to preferred sources when configured
    for evt in ranked_events:
        try:
            q = QueryEvent(evt.event_uri)
            art_kwargs: dict = dict(page=1, count=ARTICLES_PER_EVENT, sortBy="cosSim", sortByAsc=False, lang="eng")
            if sources:
                art_kwargs["sourceUri"] = QueryItems.OR(sources)
            q.setRequestedResult(RequestEventArticles(**art_kwargs))
            res = er.execQuery(q)
            raw_articles = res.get(evt.event_uri, {}).get("articles", {}).get("results", [])
            for art in raw_articles:
                body = art.get("body") or ""
                desc: str | None = None
                if body:
                    sentences = body.split(". ")
                    desc = ". ".join(sentences[:3])
                    if len(desc) > 500:
                        desc = desc[:500]
                evt.articles.append(NewsArticle(
                    headline=art.get("title", ""),
                    url=art.get("url", ""),
                    published_date=art.get("date", ""),
                    source=art.get("source", {}).get("title"),
                    description=desc,
                ))
        except Exception as e:
            print(f"[news_poll] error fetching articles for {evt.event_uri!r}: {e}")

    return ranked_events
