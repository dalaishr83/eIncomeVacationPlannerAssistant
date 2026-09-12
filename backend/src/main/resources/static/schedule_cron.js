/**
 * schedule_cron.js — Schedule Cron admin page logic.
 * Loaded exclusively on admin/schedule-cron.html.
 * Self-contained IIFE; no globals exposed.
 */
(function () {
    "use strict";

    // ── DOM refs ──────────────────────────────────────────────────────────────
    var exprInput    = document.getElementById("scExprInput");
    var addBtn       = document.getElementById("scAddBtn");
    var addMsg       = document.getElementById("scAddMsg");
    var startBtn     = document.getElementById("scStartBtn");
    var stopBtn      = document.getElementById("scStopBtn");
    var ctrlMsg      = document.getElementById("scCtrlMsg");
    var statusBadge  = document.getElementById("scStatusBadge");
    var statusText   = document.getElementById("scStatusText");
    var lastFiredEl  = document.getElementById("scLastFired");
    var tableBody    = document.getElementById("scTableBody");
    var exprCount    = document.getElementById("scExprCount");
    var selectAll    = document.getElementById("scSelectAll");
    var cardIndian   = document.getElementById("scCardIndian");
    var cardEIndkomst= document.getElementById("scCardEIndkomst");
    var cardCleanup  = document.getElementById("scCardCleanup");

    // ── Radio card behaviour ──────────────────────────────────────────────────
    function syncRadioCards() {
        var radios = document.querySelectorAll("input[name='scTeam']");
        radios.forEach(function (r) {
            var card = r.closest(".sc-radio-card");
            if (card) {
                if (r.checked) {
                    card.classList.add("sc-radio-selected");
                } else {
                    card.classList.remove("sc-radio-selected");
                }
            }
        });
    }

    // Default to Indian Team
    var defaultRadio = document.querySelector("input[name='scTeam'][value='Indian Team']");
    if (defaultRadio) { defaultRadio.checked = true; syncRadioCards(); }

    document.querySelectorAll("input[name='scTeam']").forEach(function (r) {
        r.addEventListener("change", syncRadioCards);
    });
    [cardIndian, cardEIndkomst, cardCleanup].forEach(function (card) {
        if (!card) return;
        card.addEventListener("click", function () {
            var radio = card.querySelector("input[type='radio']");
            if (radio) { radio.checked = true; syncRadioCards(); }
        });
    });

    // ── Message helpers ───────────────────────────────────────────────────────
    function setMsg(el, text, type) {
        el.textContent = text;
        el.className   = "sc-msg" + (type ? " sc-" + type : "");
    }

    // ── Status helpers ────────────────────────────────────────────────────────
    function applyStatus(status, scheduled, lastFired) {
        var isRunning = (status === "running") || (scheduled > 0);
        statusBadge.className = "sc-status-badge " + (isRunning ? "running" : "stopped");
        statusText.textContent = isRunning
            ? "Running — " + scheduled + " expression(s)"
            : "Stopped";
        if (lastFired !== undefined && lastFired !== null) {
            lastFiredEl.textContent = lastFired ? "Last fired: " + lastFired : "";
        }
        startBtn.disabled = false;
        stopBtn.disabled  = !isRunning;
    }

    // ── Load status on page open ──────────────────────────────────────────────
    function loadStatus() {
        fetch("/api/admin/cron/status")
            .then(function (r) { return r.json(); })
            .then(function (d) { applyStatus(d.status, d.scheduled, d.lastFired); })
            .catch(function () {});
    }

    // ── Load table ────────────────────────────────────────────────────────────
    function teamBadgeClass(team) {
        if (!team) return "";
        var t = team.toLowerCase();
        if (t.indexOf("indian") >= 0) {
            return "sc-team-badge sc-team-indian";
        } else if (t.indexOf("cleanup") >= 0) {
            return "sc-team-badge sc-team-cleanup";
        }
        return "sc-team-badge sc-team-eindkomst";
    }

    function formatDate(iso) {
        if (!iso) return "—";
        try {
            var d = new Date(iso);
            if (isNaN(d.getTime())) return iso;
            var dd = String(d.getDate()).padStart(2, "0");
            var mo = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"][d.getMonth()];
            var yy = d.getFullYear();
            var hh = String(d.getHours()).padStart(2, "0");
            var mm = String(d.getMinutes()).padStart(2, "0");
            return dd + " " + mo + " " + yy + " " + hh + ":" + mm;
        } catch (e) { return iso; }
    }

    function renderStatusIcon(state) {
        var isRunning = (state === "running");
        if (isRunning) {
            return "<span class='sc-state-badge state-running' title='Running'>"
                + "<svg width='12' height='12' viewBox='0 0 24 24' fill='currentColor' stroke='none'><polygon points='5,3 19,12 5,21'/></svg>"
                + "</span>";
        } else {
            return "<span class='sc-state-badge state-stopped' title='Stopped'>"
                + "<svg width='11' height='11' viewBox='0 0 24 24' fill='currentColor' stroke='none'><rect x='4' y='4' width='16' height='16' rx='2'/></svg>"
                + "</span>";
        }
    }

    function renderTable(expressions) {
        exprCount.textContent = "(" + expressions.length + ")";
        if (!expressions.length) {
            tableBody.innerHTML = "<tr class='sc-empty-row'><td colspan='6'>No cron expressions configured yet.</td></tr>";
            return;
        }
        var rows = expressions.map(function (e) {
            var badgeClass = teamBadgeClass(e.team);
            var statusIconHtml = renderStatusIcon(e.state);
            return "<tr data-id='" + esc(e.id) + "'>"
                + "<td><input type='checkbox' class='sc-cb sc-row-cb' data-id='" + esc(e.id) + "'></td>"
                + "<td style='text-align:center;'>" + statusIconHtml + "</td>"
                + "<td><span class='sc-expr-code'>" + esc(e.expression) + "</span></td>"
                + "<td><span class='" + badgeClass + "'>" + esc(e.team || "—") + "</span></td>"
                + "<td>" + formatDate(e.createdAt) + "</td>"
                + "<td style='text-align:center;'>"
                + "<button class='sc-delete-btn' data-id='" + esc(e.id) + "' title='Delete this expression'>"
                + "<svg width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>"
                + "<polyline points='3 6 5 6 21 6'/>"
                + "<path d='M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6'/>"
                + "<path d='M10 11v6'/><path d='M14 11v6'/>"
                + "<path d='M9 6V4h6v2'/>"
                + "</svg>"
                + "</button>"
                + "</td>"
                + "</tr>";
        }).join("");
        tableBody.innerHTML = rows;
        // Bind delete buttons
        tableBody.querySelectorAll(".sc-delete-btn").forEach(function (btn) {
            btn.addEventListener("click", function () { deleteExpression(btn.getAttribute("data-id")); });
        });
        // Sync select-all
        selectAll.checked = false;
    }

    function esc(s) {
        if (!s) return "";
        return String(s)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }

    function loadExpressions() {
        fetch("/api/admin/cron/expressions")
            .then(function (r) { return r.json(); })
            .then(function (d) { renderTable(d.expressions || []); })
            .catch(function () {
                tableBody.innerHTML = "<tr class='sc-empty-row'><td colspan='6'>Failed to load expressions.</td></tr>";
            });
    }

    // ── Select all checkbox ───────────────────────────────────────────────────
    selectAll.addEventListener("change", function () {
        tableBody.querySelectorAll(".sc-row-cb").forEach(function (cb) {
            cb.checked = selectAll.checked;
        });
    });

    // ── Add expression ────────────────────────────────────────────────────────
    addBtn.addEventListener("click", function () {
        var expr = exprInput.value.trim();
        var teamRadio = document.querySelector("input[name='scTeam']:checked");
        var team = teamRadio ? teamRadio.value : "";

        if (!expr) { setMsg(addMsg, "Please enter at least one cron expression.", "error"); return; }
        if (!team) { setMsg(addMsg, "Please select a team.", "error"); return; }

        addBtn.disabled = true;
        setMsg(addMsg, "", "");

        fetch("/api/admin/cron/expressions", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ expression: expr, team: team })
        })
        .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
        .then(function (res) {
            if (res.ok) {
                var msg = res.d.message || (res.d.added + " expression(s) added.");
                if (res.d.errors && res.d.errors.length) {
                    msg += " Warnings: " + res.d.errors.join("; ");
                }
                setMsg(addMsg, msg, "success");
                exprInput.value = "";
                renderTable(res.d.expressions || []);
            } else {
                setMsg(addMsg, res.d.error || "Failed to add expression.", "error");
            }
        })
        .catch(function () { setMsg(addMsg, "Network error. Please try again.", "error"); })
        .finally(function () { addBtn.disabled = false; });
    });

    exprInput.addEventListener("keydown", function (e) {
        if (e.key === "Enter") addBtn.click();
    });

    // ── Delete expression ─────────────────────────────────────────────────────
    function deleteExpression(id) {
        if (!id) return;
        fetch("/api/admin/cron/expressions", {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ id: id })
        })
        .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
        .then(function (res) {
            if (res.ok) {
                renderTable(res.d.expressions || []);
                setMsg(addMsg, res.d.message || "Expression deleted.", "success");
            } else {
                setMsg(addMsg, res.d.error || "Failed to delete.", "error");
            }
        })
        .catch(function () { setMsg(addMsg, "Network error on delete.", "error"); });
    }

    // ── Get selected expression IDs ───────────────────────────────────────────
    function getSelectedIds() {
        var checked = tableBody.querySelectorAll(".sc-row-cb:checked");
        var ids = [];
        checked.forEach(function (cb) {
            var id = cb.getAttribute("data-id");
            if (id) ids.push(id);
        });
        return ids;
    }

    // ── Start ─────────────────────────────────────────────────────────────────
    startBtn.addEventListener("click", function () {
        setMsg(ctrlMsg, "", "");
        var selectedIds = getSelectedIds();
        if (!selectedIds.length) {
            setMsg(ctrlMsg, "Please select at least one cron expression to start.", "error");
            return;
        }

        startBtn.disabled = true;
        fetch("/api/admin/cron/start", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ ids: selectedIds })
        })
            .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
            .then(function (res) {
                if (res.ok) {
                    setMsg(ctrlMsg, res.d.message || "Scheduler started.", "success");
                    applyStatus(res.d.status, res.d.scheduled, null);
                    if (res.d.expressions) {
                        renderTable(res.d.expressions);
                    } else {
                        loadExpressions();
                    }
                } else {
                    setMsg(ctrlMsg, res.d.error || "Failed to start scheduler.", "error");
                }
            })
            .catch(function () {
                setMsg(ctrlMsg, "Network error. Could not start scheduler.", "error");
            })
            .finally(function () {
                startBtn.disabled = false;
            });
    });

    // ── Stop ──────────────────────────────────────────────────────────────────
    stopBtn.addEventListener("click", function () {
        stopBtn.disabled = true;
        setMsg(ctrlMsg, "", "");
        var selectedIds = getSelectedIds();
        var body = selectedIds.length ? JSON.stringify({ ids: selectedIds }) : JSON.stringify({});
        fetch("/api/admin/cron/stop", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: body
        })
            .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
            .then(function (res) {
                if (res.ok) {
                    setMsg(ctrlMsg, res.d.message || "Scheduler stopped.", "success");
                    applyStatus(res.d.status, res.d.scheduled, null);
                    if (res.d.expressions) {
                        renderTable(res.d.expressions);
                    } else {
                        loadExpressions();
                    }
                } else {
                    setMsg(ctrlMsg, res.d.error || "Failed to stop scheduler.", "error");
                    stopBtn.disabled = false;
                }
            })
            .catch(function () {
                setMsg(ctrlMsg, "Network error. Could not stop scheduler.", "error");
                stopBtn.disabled = false;
            })
            .finally(function () {
                stopBtn.disabled = false;
            });
    });

    // ── Initialise ────────────────────────────────────────────────────────────
    loadStatus();
    loadExpressions();

    // ── Responsive collapsible layout ─────────────────────────────────────────
    //
    // Strategy:
    //   1. Measure the natural (expanded) height of the two compact cards to
    //      determine the total height required to display all three sections.
    //   2. If the available sc-page inner height is smaller than that total,
    //      activate sc-collapsed-mode on sc-page.
    //   3. In collapsed mode, Configured Expressions is open by default and
    //      receives all remaining space. Clicking any section title switches
    //      which section is open.
    //   4. When the viewport grows large enough, collapsed mode is removed and
    //      all three sections return to their normal expanded static layout.
    //
    (function () {
        var page       = document.querySelector(".sc-page");
        var addCard    = document.getElementById("scAddCard");
        var ctrlCard   = document.getElementById("scCtrlCard");
        var tableCard  = document.querySelector(".sc-table-card");
        var addTitle   = document.getElementById("scAddCardTitle");
        var ctrlTitle  = document.getElementById("scCtrlCardTitle");
        var tableHdr   = tableCard ? tableCard.querySelector(".sc-table-header") : null;

        if (!page || !addCard || !ctrlCard || !tableCard) return;

        var collapsedMode = false;
        var TITLE_BAR_H   = 38;  // h1 + subtitle row
        var GAP           = 10;  // sc-page gap
        var PAGE_PADDING  = 28;  // top + bottom padding of sc-page
        var MIN_TABLE_H   = 160; // minimum useful height for the table section

        function getExpandedHeights() {
            // Temporarily remove collapsed mode to measure natural sizes
            var wasCollapsed = collapsedMode;
            if (wasCollapsed) {
                page.classList.remove("sc-collapsed-mode");
                addCard.classList.remove("sc-open");
                ctrlCard.classList.remove("sc-open");
                tableCard.classList.remove("sc-open");
            }
            var addH  = addCard.offsetHeight;
            var ctrlH = ctrlCard.offsetHeight;
            var total = TITLE_BAR_H + PAGE_PADDING + (GAP * 3) + addH + ctrlH + MIN_TABLE_H;
            if (wasCollapsed) {
                page.classList.add("sc-collapsed-mode");
                restoreOpen();
            }
            return total;
        }

        var currentOpen = "table"; // "add" | "ctrl" | "table"

        function restoreOpen() {
            addCard.classList.toggle("sc-open",   currentOpen === "add");
            ctrlCard.classList.toggle("sc-open",  currentOpen === "ctrl");
            tableCard.classList.toggle("sc-open", currentOpen === "table");
        }

        function openSection(name) {
            currentOpen = name;
            restoreOpen();
        }

        function evaluate() {
            var needed    = getExpandedHeights();
            var available = page.offsetHeight;
            var shouldCollapse = available > 0 && available < needed;

            if (shouldCollapse && !collapsedMode) {
                collapsedMode = true;
                page.classList.add("sc-collapsed-mode");
                openSection("table");
            } else if (!shouldCollapse && collapsedMode) {
                collapsedMode = false;
                page.classList.remove("sc-collapsed-mode");
                addCard.classList.remove("sc-open");
                ctrlCard.classList.remove("sc-open");
                tableCard.classList.remove("sc-open");
            }
        }

        function attachToggle(titleEl, name) {
            if (!titleEl) return;
            titleEl.addEventListener("click", function () {
                if (!collapsedMode) return;
                openSection(name);
            });
        }
        attachToggle(addTitle,  "add");
        attachToggle(ctrlTitle, "ctrl");
        attachToggle(tableHdr,  "table");

        if (typeof ResizeObserver !== "undefined") {
            var ro = new ResizeObserver(function () { evaluate(); });
            ro.observe(page);
        } else {
            window.addEventListener("resize", function () { evaluate(); });
        }

        setTimeout(evaluate, 0);
    })();

})();
