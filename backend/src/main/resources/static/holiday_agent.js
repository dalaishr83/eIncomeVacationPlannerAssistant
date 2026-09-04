/**
 * holiday_agent.js — Vacation Planner Assistant SPA (compiled from TypeScript)
 * All client-side logic in a single IIFE.
 */
(function () {
    "use strict";

    // ── State ──────────────────────────────────────────────────────────────────
    let employees = [];
    let currentYear = new Date().getFullYear();
    let isThinking = false;
    /** Mirrors app.cross-year-booking-override from the server. Fetched once on boot. */
    let crossYearBookingOverride = false;

    // ── DOM refs ───────────────────────────────────────────────────────────────
    const messagesEl      = document.getElementById("messages");
    const welcomeCard     = document.getElementById("welcomeCard");
    const messageInput    = document.getElementById("messageInput");
    const sendBtn         = document.getElementById("sendBtn");
    const fileInput       = document.getElementById("fileInput");
    const uploadZone      = document.getElementById("uploadZone");
    const fileList        = document.getElementById("fileList");
    const yearSelect      = document.getElementById("yearSelect");
    const employeeList    = document.getElementById("employeeList");
    const quickChips      = document.getElementById("quickChips");
    const clearHistoryBtn = document.getElementById("clearHistoryBtn");
    const refreshBtn      = document.getElementById("refreshBtn");
    const newChatBtn      = document.getElementById("newChatBtn");
    const hamburgerBtn    = document.getElementById("hamburgerBtn");
    const sidebar         = document.getElementById("sidebar");
    const overlay         = document.getElementById("overlay");
    const statusDot       = document.getElementById("statusDot");
    const topbarSubtitle  = document.getElementById("topbarSubtitle");

    // ── Boot sequence ──────────────────────────────────────────────────────────
    Promise.all([fetchEmployees(), fetchYears(), fetchFiles(), fetchConfig()]).catch(() => {});

    async function fetchConfig() {
        try {
            const res = await fetch("/api/config");
            if (!res.ok) return;
            const data = await res.json();
            if (typeof data.crossYearBookingOverride === "boolean") {
                crossYearBookingOverride = data.crossYearBookingOverride;
            }
        } catch (_) { /* non-critical — default false is safe */ }
    }

    // ── Chat ───────────────────────────────────────────────────────────────────

    async function sendMessage() {
        const msg = messageInput.value.trim();
        if (!msg || isThinking) return;

        // Show a human-readable label instead of the raw form-submission prefix
        const displayMsg = msg.startsWith("__VACATION_FORM__:")
            ? "✅ Form submitted"
            : msg;
        appendMessage(displayMsg, "user");
        messageInput.value = "";
        autoResizeTextarea();
        setThinking(true);
        const thinking = appendThinking();

        try {
            const res = await fetch("/api/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ message: msg }),
            });
            const data = await res.json();
            thinking.remove();
            if (data.error) {
                appendMessage("⚠️ " + data.error, "bot");
            } else if (data.type === "vacation_form") {
                // Render the inline vacation form widget instead of a text bubble.
                hideWelcomeCard();
                let formPayload = null;
                try { formPayload = JSON.parse(data.reply); } catch (e) {}
                if (formPayload) {
                    const msgEl = appendMessage("", "bot", "vacation_prompt");
                    renderVacationForm(formPayload, msgEl);
                } else {
                    appendMessage("Could not load the vacation form. Please type 'cancel' to abort.", "bot");
                }
            } else if (data.tableData && data.tableData.length > 0) {
                // Table-only mode: skip the LLM text bubble and show just the table.
                hideWelcomeCard();
                const msgEl = document.createElement("div");
                msgEl.className = "message bot";
                msgEl.appendChild(makeBotAvatar());
                messagesEl.appendChild(msgEl);
                appendLeaveTable(msgEl, data.tableData);
                messagesEl.scrollTop = messagesEl.scrollHeight;
            } else {
                appendMessage(data.reply, "bot", data.type);
            }
        } catch (e) {
            thinking.remove();
            appendMessage("⚠️ Network error. Please try again.", "bot");
        } finally {
            setThinking(false);
            messageInput.focus();
        }
    }

    // ── Message rendering ──────────────────────────────────────────────────────

    function hideWelcomeCard() {
        if (welcomeCard && welcomeCard.parentNode) welcomeCard.remove();
    }

    function showWelcomeCard() {
        if (!document.getElementById("welcomeCard")) {
            const greetingName = messagesEl.getAttribute("data-greeting-name") || "there";
            const card = document.createElement("div");
            card.id = "welcomeCard";
            card.className = "welcome-card";
            card.innerHTML = messagesEl.querySelector ? "" : "";
            // Re-insert the original welcome card markup
            card.innerHTML =
                '<div class="welcome-robot">' +
                '<svg width="120" height="130" viewBox="0 0 120 130" fill="none">' +
                '<g class="wc-robot-group">' +
                '<rect x="58" y="4" width="4" height="18" rx="2" fill="#3b5bdb"/>' +
                '<circle class="wc-antenna-tip" cx="60" cy="4" r="5" fill="#60a5fa"/>' +
                '<rect x="26" y="20" width="68" height="48" rx="14" fill="#3b5bdb"/>' +
                '<rect class="wc-eye" x="36" y="34" width="18" height="18" rx="5" fill="#fff"/>' +
                '<rect class="wc-eye" x="66" y="34" width="18" height="18" rx="5" fill="#fff"/>' +
                '<circle cx="45" cy="43" r="6" fill="#1e3a8a"/>' +
                '<circle cx="75" cy="43" r="6" fill="#1e3a8a"/>' +
                '<circle cx="47" cy="40" r="2.5" fill="#93c5fd"/>' +
                '<circle cx="77" cy="40" r="2.5" fill="#93c5fd"/>' +
                '<rect class="wc-mouth" x="42" y="56" width="36" height="6" rx="3" fill="#93c5fd"/>' +
                '<rect x="54" y="68" width="12" height="8" rx="3" fill="#2952cc"/>' +
                '<rect x="18" y="76" width="84" height="46" rx="14" fill="#3b5bdb"/>' +
                '<circle cx="42" cy="99" r="6" fill="#1e3a8a"/>' +
                '<circle cx="60" cy="99" r="6" fill="#60a5fa"/>' +
                '<circle cx="78" cy="99" r="6" fill="#1e3a8a"/>' +
                '<rect x="32" y="112" width="56" height="4" rx="2" fill="#2952cc"/>' +
                '<rect class="wc-arm-l" x="2" y="78" width="16" height="32" rx="8" fill="#2952cc"/>' +
                '<rect class="wc-arm-r" x="102" y="78" width="16" height="32" rx="8" fill="#2952cc"/>' +
                '<rect x="32" y="122" width="20" height="8" rx="4" fill="#2952cc"/>' +
                '<rect x="68" y="122" width="20" height="8" rx="4" fill="#2952cc"/>' +
                '</g></svg></div>' +
                '<h2 class="welcome-title">Hello, ' + greetingName + '! I\'m your Leave Assistant.</h2>' +
                '<p class="welcome-sub">Ask me anything about employee holidays and leave—I\'ll answer strictly based<br>' +
                'on your Excel data. You can also add new leave entries or delete existing ones.<br>' +
                'Contact your administrator to upload or manage files.</p>';
            messagesEl.insertBefore(card, messagesEl.firstChild);
        }
    }

    // Inline robot SVG reused for every bot avatar
    const BOT_AVATAR_SVG =
        '<svg width="20" height="20" viewBox="0 0 100 100" fill="none">' +
        '<rect x="28" y="30" width="44" height="34" rx="8" fill="white" fill-opacity="0.95"/>' +
        '<rect x="36" y="39" width="10" height="10" rx="3" fill="#2563eb"/>' +
        '<rect x="54" y="39" width="10" height="10" rx="3" fill="#2563eb"/>' +
        '<rect x="40" y="54" width="20" height="4" rx="2" fill="#93c5fd"/>' +
        '<rect x="46" y="20" width="8" height="10" rx="3" fill="white" fill-opacity="0.9"/>' +
        '<circle cx="50" cy="17" r="4" fill="#bfdbfe"/>' +
        '<rect x="18" y="38" width="10" height="18" rx="5" fill="white" fill-opacity="0.8"/>' +
        '<rect x="72" y="38" width="10" height="18" rx="5" fill="white" fill-opacity="0.8"/>' +
        '<rect x="32" y="64" width="12" height="16" rx="5" fill="white" fill-opacity="0.8"/>' +
        '<rect x="56" y="64" width="12" height="16" rx="5" fill="white" fill-opacity="0.8"/>' +
        '</svg>';

    function makeBotAvatar() {
        const av = document.createElement("div");
        av.className = "bot-avatar";
        av.innerHTML = BOT_AVATAR_SVG;
        return av;
    }

    function appendMessage(text, role, type = "text") {
        hideWelcomeCard();
        const div = document.createElement("div");
        div.className = "message " + role;
        if (type === "vacation_prompt") div.classList.add("vacation-prompt");

        // Add avatar for bot messages
        if (role === "bot") div.appendChild(makeBotAvatar());

        const bubble = document.createElement("div");
        bubble.className = "bubble";

        if (type === "report") {
            const match = text.match(/report-file:\s*(.+)/i);
            const display = text.replace(/\nreport-file:[^\n]*/i, "").trim();
            bubble.innerHTML = renderMarkdown(display);
            if (match) {
                const fname = match[1].trim().split(/[/\\]/).pop() || "";
                const link = document.createElement("a");
                link.href = "/api/reports/" + fname;
                link.target = "_blank";
                link.className = "report-link";
                link.textContent = "Open HTML Report";
                bubble.appendChild(link);
            }
        } else {
            bubble.innerHTML = renderMarkdown(text);
        }

        div.appendChild(bubble);
        messagesEl.appendChild(div);
        messagesEl.scrollTop = messagesEl.scrollHeight;
        return div;
    }

    function appendThinking() {
        hideWelcomeCard();
        // Show "Thinking…" in topbar subtitle
        if (topbarSubtitle) topbarSubtitle.textContent = "Thinking…";

        const div = document.createElement("div");
        div.className = "message bot";
        div.id = "thinkingRow";
        div.appendChild(makeBotAvatar());

        const dots = document.createElement("div");
        dots.className = "thinking-dots";
        dots.innerHTML = "<span></span><span></span><span></span>";
        div.appendChild(dots);

        messagesEl.appendChild(div);
        messagesEl.scrollTop = messagesEl.scrollHeight;
        return div;
    }

    function renderMarkdown(text) {
        let html = text.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
        html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
        html = html.replace(/\n/g, "<br>");
        return html;
    }

    // ── Leave table ────────────────────────────────────────────────────────────

    /**
     * Appends a structured HTML table below the bot message bubble for date-range queries.
     * tableData: [{employee_name, month, total_vacation, leave_types}, ...]
     * A "Copy to clipboard" button allows the full table text to be copied.
     */
    function appendLeaveTable(msgEl, tableData) {
        const wrap = document.createElement("div");
        wrap.className = "leave-table-wrap";

        // ── Table ──────────────────────────────────────────────────────────────
        const table = document.createElement("table");
        table.className = "leave-table";

        const thead = document.createElement("thead");
        thead.innerHTML =
            "<tr>" +
            "<th>Employee Name</th>" +
            "<th>Month</th>" +
            "<th class=\"leave-table-num\">Total Vacation</th>" +
            "</tr>";
        table.appendChild(thead);

        const tbody = document.createElement("tbody");
        tableData.forEach(function (row) {
            const tr = document.createElement("tr");
            tr.innerHTML =
                "<td>" + escapeHtml(row.employee_name || "") + "</td>" +
                "<td>" + escapeHtml(row.month || "") + "</td>" +
                "<td class=\"leave-table-num\">" + (row.total_vacation != null ? row.total_vacation : "") + "</td>";
            tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        wrap.appendChild(table);

        // ── Copy button ────────────────────────────────────────────────────────
        const copyBtn = document.createElement("button");
        copyBtn.className = "leave-table-copy";
        copyBtn.textContent = "Copy table";
        copyBtn.addEventListener("click", function () {
            const lines = ["Employee Name\tMonth\tTotal Vacation"];
            tableData.forEach(function (row) {
                lines.push(
                    [row.employee_name || "", row.month || "",
                     row.total_vacation != null ? row.total_vacation : ""].join("\t")
                );
            });
            const text = lines.join("\n");

            function onSuccess() {
                copyBtn.textContent = "Copied!";
                setTimeout(function () { copyBtn.textContent = "Copy table"; }, 2000);
            }
            function onFailure() {
                copyBtn.textContent = "Copy failed";
                setTimeout(function () { copyBtn.textContent = "Copy table"; }, 2000);
            }

            if (navigator.clipboard && navigator.clipboard.writeText) {
                // Secure context (HTTPS / localhost): use the modern async API.
                navigator.clipboard.writeText(text).then(onSuccess).catch(onFailure);
            } else {
                // Insecure HTTP origin: fall back to the legacy execCommand approach.
                try {
                    const ta = document.createElement("textarea");
                    ta.value = text;
                    ta.style.cssText = "position:fixed;top:-9999px;left:-9999px;opacity:0";
                    document.body.appendChild(ta);
                    ta.focus();
                    ta.select();
                    document.execCommand("copy");
                    document.body.removeChild(ta);
                    onSuccess();
                } catch (e) {
                    onFailure();
                }
            }
        });
        wrap.appendChild(copyBtn);

        msgEl.appendChild(wrap);
        messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    // ── Inline vacation form ───────────────────────────────────────────────────

    /**
     * Renders an interactive vacation form inside the bot message bubble (msgEl).
     * payload: { employeeName, wizardType ("add"|"delete"), types: [{code,label}] }
     *
     * On submit the form encodes the selection as a chat message with the
     * __VACATION_FORM__: prefix and calls sendMessage() so the existing wizard
     * flow handles it server-side.
     */
    function renderVacationForm(payload, msgEl) {
        const isAdd    = payload.wizardType === "add";
        const empName  = payload.employeeName || "";
        const types    = Array.isArray(payload.types) ? payload.types : [];

        // minDate = today (no past dates)
        // maxDate: during Q4 (Oct–Dec) OR when the server-side override flag is enabled,
        //          allow booking up to 31 Mar of next year; otherwise cap at 31 Dec.
        const now = new Date();
        const minDate = now.getFullYear() + "-" +
            String(now.getMonth() + 1).padStart(2, "0") + "-" +
            String(now.getDate()).padStart(2, "0");
        const isQ4 = now.getMonth() >= 9; // 0-based: Oct=9, Nov=10, Dec=11
        const allowNextYearQ1 = isQ4 || crossYearBookingOverride;
        let maxDate;
        if (allowNextYearQ1) {
            // Allow up to 31 March of the following year.
            maxDate = (now.getFullYear() + 1) + "-03-31";
        } else {
            // Current year cap: 31 December.
            maxDate = now.getFullYear() + "-12-31";
        }

        const bubble = msgEl.querySelector(".bubble");
        if (!bubble) return;

        // ── Build the form DOM ────────────────────────────────────────────────
        const form = document.createElement("form");
        form.className = "vac-form";
        form.noValidate = true;

        // Title
        const title = document.createElement("div");
        title.className = "vac-form-title";
        title.textContent = (isAdd ? "Add vacation" : "Delete vacation") + " for " + empName;
        form.appendChild(title);

        // Radio buttons (add only)
        let radioInputs = [];
        if (isAdd && types.length > 0) {
            const typeLabel = document.createElement("div");
            typeLabel.className = "vac-form-section-label";
            typeLabel.textContent = "Vacation type";
            form.appendChild(typeLabel);

            const radioGroup = document.createElement("div");
            radioGroup.className = "vac-form-radio-group";

            types.forEach(function (t, idx) {
                const lbl = document.createElement("label");
                lbl.className = "vac-form-radio-label";

                const radio = document.createElement("input");
                radio.type  = "radio";
                radio.name  = "vac_type";
                radio.value = t.code;
                if (idx === 0) radio.checked = true;

                const text = document.createTextNode(t.label);
                const codePill = document.createElement("span");
                codePill.className = "vac-form-radio-code";
                codePill.textContent = t.code;

                lbl.appendChild(radio);
                lbl.appendChild(text);
                lbl.appendChild(codePill);

                // Highlight selected row
                radio.addEventListener("change", function () {
                    radioGroup.querySelectorAll(".vac-form-radio-label")
                        .forEach(function (el) { el.classList.remove("selected"); });
                    lbl.classList.add("selected");
                });
                if (idx === 0) lbl.classList.add("selected");

                radioGroup.appendChild(lbl);
                radioInputs.push(radio);
            });
            form.appendChild(radioGroup);
        }

        // Warning note (delete only)
        if (!isAdd) {
            const warn = document.createElement("div");
            warn.className = "vac-form-warning";
            warn.innerHTML =
                '<svg class="vac-form-warning-icon" width="15" height="15" viewBox="0 0 20 20" fill="none">' +
                '<path d="M10 2L2 17h16L10 2z" stroke="#92400e" stroke-width="1.5" stroke-linejoin="round" fill="#fde68a"/>' +
                '<path d="M10 8v4M10 13.5v.5" stroke="#92400e" stroke-width="1.5" stroke-linecap="round"/>' +
                '</svg>' +
                '<span>Vacation entries in the selected date range will be <strong>permanently removed</strong>. This action cannot be undone.</span>';
            form.appendChild(warn);
        }

        // Date section label
        const dateLabel = document.createElement("div");
        dateLabel.className = "vac-form-section-label";
        dateLabel.textContent = isAdd ? "Date range" : "Date range to delete";
        form.appendChild(dateLabel);

        // Date inputs
        const datesRow = document.createElement("div");
        datesRow.className = "vac-form-dates";

        function makeDateField(labelText, inputId) {
            const field = document.createElement("div");
            field.className = "vac-form-date-field";
            const lbl = document.createElement("div");
            lbl.className = "vac-form-date-label";
            lbl.textContent = labelText;
            const inp = document.createElement("input");
            inp.type  = "date";
            inp.id    = inputId;
            inp.className = "vac-form-date-input";
            inp.min   = minDate;
            inp.max   = maxDate;
            inp.required = true;
            field.appendChild(lbl);
            field.appendChild(inp);
            return { field, inp };
        }

        const { field: startField, inp: startInput } = makeDateField("Start date", "vac_start_" + Date.now());
        const { field: endField,   inp: endInput   } = makeDateField("End date",   "vac_end_"   + Date.now() + 1);
        datesRow.appendChild(startField);
        datesRow.appendChild(endField);
        form.appendChild(datesRow);

        // Ensure end ≥ start and both stay within the detected year when start changes
        startInput.addEventListener("change", function () {
            if (endInput.value && endInput.value < startInput.value) {
                endInput.value = startInput.value;
            }
            endInput.min = startInput.value || minDate;
            endInput.max = maxDate;
        });

        // Inline error display
        const errDiv = document.createElement("div");
        errDiv.className = "vac-form-error";
        form.appendChild(errDiv);

        function showError(msg) {
            errDiv.textContent = msg;
            errDiv.classList.add("visible");
        }
        function clearError() {
            errDiv.textContent = "";
            errDiv.classList.remove("visible");
        }

        // Submit row
        const submitRow = document.createElement("div");
        submitRow.className = "vac-form-submit-row";

        const cancelBtn = document.createElement("button");
        cancelBtn.type = "button";
        cancelBtn.className = "vac-form-cancel-btn";
        cancelBtn.textContent = "Cancel";

        const submitBtn = document.createElement("button");
        submitBtn.type = "submit";
        submitBtn.className = "vac-form-submit-btn" + (isAdd ? "" : " vac-form-submit-btn--danger");
        submitBtn.textContent = isAdd ? "Add vacation" : "Delete vacation";

        submitRow.appendChild(cancelBtn);
        submitRow.appendChild(submitBtn);
        form.appendChild(submitRow);

        // ── Wire events ───────────────────────────────────────────────────────

        cancelBtn.addEventListener("click", function () {
            disableForm();
            messageInput.value = "cancel";
            sendMessage();
        });

        form.addEventListener("submit", function (e) {
            e.preventDefault();
            clearError();

            // Validate type selection (add only)
            let selectedCode = "";
            if (isAdd) {
                const checked = form.querySelector("input[name='vac_type']:checked");
                if (!checked) { showError("Please select a vacation type."); return; }
                selectedCode = checked.value;
            } else {
                selectedCode = "DELETE";
            }

            // Validate dates
            const startVal = startInput.value;
            const endVal   = endInput.value;
            if (!startVal) { showError("Please select a start date."); return; }
            if (!endVal)   { showError("Please select an end date."); return; }
            if (endVal < startVal) { showError("Start date cannot be greater than end date."); return; }
            if (startVal < minDate) {
                showError("Past dates are not allowed for start date."); return;
            }
            if (startVal > maxDate) {
                showError("Start date is beyond the allowed booking window."); return;
            }
            if (endVal < minDate) {
                showError("Past dates are not allowed for end date."); return;
            }
            if (endVal > maxDate) {
                const limitLabel = allowNextYearQ1 ? "31 March " + (now.getFullYear() + 1) : "31 December " + now.getFullYear();
                const hint = allowNextYearQ1 ? "" : " Advance booking for Q1 of next year is only available during Q4.";
                showError("End date cannot be later than " + limitLabel + "." + hint); return;
            }

            disableForm();

            // Encode as structured chat message and send via existing mechanism
            const encoded = "__VACATION_FORM__:" + selectedCode + "|" + startVal + "|" + endVal;
            messageInput.value = encoded;
            sendMessage();
        });

        // Pressing Enter inside a date input submits the form (not the outer textarea)
        [startInput, endInput].forEach(function (inp) {
            inp.addEventListener("keydown", function (e) {
                if (e.key === "Enter") { e.preventDefault(); e.stopPropagation(); form.requestSubmit(); }
            });
        });

        function disableForm() {
            submitBtn.disabled  = true;
            cancelBtn.disabled  = true;
            radioInputs.forEach(function (r) { r.disabled = true; });
            startInput.disabled = true;
            endInput.disabled   = true;
        }

        bubble.appendChild(form);
        messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    // ── File upload ────────────────────────────────────────────────────────────

    async function uploadFile(file) {
        if (!file.name.toLowerCase().endsWith(".xlsx")) {
            appendMessage("⚠️ Only .xlsx files are supported.", "bot");
            return;
        }
        const thinking = appendMessage("Uploading and parsing file…", "bot");
        setThinking(true);

        const formData = new FormData();
        formData.append("file", file);

        try {
            const res = await fetch("/api/upload", { method: "POST", body: formData });
            const data = await res.json();
            thinking.remove();
            setThinking(false);

            if (data.error) {
                appendMessage("⚠️ " + data.error, "bot");
                return;
            }

            employees = data.employees || [];
            renderEmployees();
            renderFiles(data.files || []);
            buildQuickChips();
            await fetchYears();
            appendMessage("✅ " + data.message + "\n\nYou can now ask questions about employee leave data.", "bot");
        } catch (e) {
            thinking.remove();
            setThinking(false);
            appendMessage("⚠️ Upload failed. Please try again.", "bot");
        }
    }

    // ── API helpers ────────────────────────────────────────────────────────────

    async function fetchEmployees() {
        try {
            const res = await fetch("/api/employees");
            const data = await res.json();
            employees = data.employees || [];
            renderEmployees();
            buildQuickChips();
        } catch {}
    }

    async function fetchYears() {
        try {
            const res = await fetch("/api/years");
            const data = await res.json();
            const years = data.years || [];
            renderYearSelector(years);
        } catch {}
    }

    async function fetchFiles() {
        try {
            const res = await fetch("/api/files");
            const data = await res.json();
            renderFiles(data.files || []);
        } catch {}
    }

    async function switchFile(path) {
        setThinking(true);
        try {
            const res = await fetch("/api/switch-file", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ path }),
            });
            const data = await res.json();
            if (data.error) { appendMessage("⚠️ " + data.error, "bot"); return; }
            employees = data.employees || [];
            renderEmployees();
            renderFiles(data.files || []);
            buildQuickChips();
            await fetchYears();
            appendMessage("Switched to " + path.split(/[/\\]/).pop() + ". " + employees.length + " employees loaded.", "bot");
        } catch {
            appendMessage("⚠️ Failed to switch file.", "bot");
        } finally {
            setThinking(false);
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    function renderEmployees() {
        if (!employeeList) return;
        employeeList.innerHTML = "";
        employees.forEach(name => {
            const li = document.createElement("li");
            // Blue dot indicator
            const dot = document.createElement("span");
            dot.className = "emp-dot";
            li.appendChild(dot);
            const label = document.createElement("span");
            label.textContent = name;
            li.appendChild(label);
            li.title = name;
            li.addEventListener("click", () => {
                messageInput.value = "How many days has " + name + " taken in " + currentYear + "?";
                messageInput.focus();
            });
            employeeList.appendChild(li);
        });
        // Update topbar subtitle
        if (topbarSubtitle) {
            topbarSubtitle.textContent = employees.length > 0
                ? employees.length + " employee(s) loaded"
                : "No file loaded";
        }
    }

    function renderFiles(files) {
        if (!fileList) return;
        fileList.innerHTML = "";
        files.forEach(f => {
            const li = document.createElement("li");
            const nameSpan = document.createElement("span");
            nameSpan.className = "file-name";
            nameSpan.textContent = f.name;
            li.appendChild(nameSpan);
            if (f.active) {
                li.classList.add("active");
                const badge = document.createElement("span");
                badge.className = "file-badge";
                badge.textContent = "Active";
                li.appendChild(badge);
            }
            li.title = f.path;
            li.addEventListener("click", () => switchFile(f.path));
            fileList.appendChild(li);
        });
    }

    function renderYearSelector(years) {
        if (!yearSelect) return;
        yearSelect.innerHTML = "";
        if (years.length === 0) {
            const opt = document.createElement("option");
            opt.value = String(currentYear);
            opt.textContent = String(currentYear);
            yearSelect.appendChild(opt);
            return;
        }
        years.forEach(y => {
            const opt = document.createElement("option");
            opt.value = String(y);
            opt.textContent = String(y);
            yearSelect.appendChild(opt);
        });
        currentYear = years[0];
        buildQuickChips();
    }

    function buildQuickChips() {
        if (!quickChips) return;
        quickChips.innerHTML = "";
        const loginUsername = (messagesEl
            ? messagesEl.getAttribute("data-login-username")
            : null) || employees[0] || "me";
        const templates = [
            // ── Full-year (Rule 5) ────────────────────────────────────────────
            "Show leave summary for {name} in {year}",
            "How many leave days does {name} have in {year}?",
            "What is {name}'s remaining leave for {year}?",
            "What is {name}'s leave utilization rate in {year}?",
            "Break down {name}'s leave types for {year}",
            "What is {name}'s longest leave streak in {year}?",
            // ── Single-month generic (Rule 3) ─────────────────────────────────
            "How many leave days does {name} have in March {year}?",
            "How many days did {name} take in April {year}?",
            // ── Single-month type-specific (Rule 4) ───────────────────────────
            "How many V leave days does {name} have in March {year}?",
            "How many PC leave days does {name} have in April {year}?",
            "How many Public Holiday days does {name} have in January {year}?",
            // ── Date query ────────────────────────────────────────────────────
            "Who is on leave on 15 March {year}?",
            "Who are on leave between 31 August {year} and 7 September {year}",
            // ── Range generic (Rule 1) ────────────────────────────────────────
            "How many days does {name} have from January to March {year}?",
            "How many leave days does {name} have from April to June {year}?",
            // ── Range type-specific (Rule 2) ──────────────────────────────────
            "How many V leave days does {name} have from January to March {year}?",
            "How many PC leave days does {name} have from January to June {year}?",
            "How many Public Holiday days does {name} have from January to March {year}?",
            // ── All-employees ─────────────────────────────────────────────────
            "Show all employees' leave totals for {year}",
            "Which employees have the most leave in {year}?",
            "Which employees have the least leave in {year}?",
            // ── Actions ───────────────────────────────────────────────────────
            "Generate leave report for {name} in {year}",
            "Add vacation for {name}",
            "Delete vacation for {name}",
        ];
        templates.forEach(tpl => {
            const label = tpl.replace(/\{name\}/g, loginUsername).replace(/\{year\}/g, String(currentYear));
            const chip = document.createElement("button");
            chip.className = "chip";
            chip.textContent = label;
            chip.addEventListener("click", () => {
                messageInput.value = label;
                messageInput.focus();
            });
            quickChips.appendChild(chip);
        });
    }

    function setThinking(state) {
        isThinking = state;
        sendBtn.disabled = state;
        messageInput.disabled = state;
        // Restore topbar subtitle when done thinking
        if (!state && topbarSubtitle) {
            topbarSubtitle.textContent = employees.length > 0
                ? employees.length + " employee(s) loaded"
                : "No file loaded";
        }
    }

    function autoResizeTextarea() {
        messageInput.style.height = "auto";
        messageInput.style.height = Math.min(messageInput.scrollHeight, 160) + "px";
    }

    // ── Event listeners ────────────────────────────────────────────────────────

    sendBtn.addEventListener("click", sendMessage);

    messageInput.addEventListener("keydown", e => {
        if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
    });
    messageInput.addEventListener("input", autoResizeTextarea);

    if (uploadZone && fileInput) {
        uploadZone.addEventListener("click", () => fileInput.click());
        fileInput.addEventListener("change", () => {
            if (fileInput.files && fileInput.files[0]) uploadFile(fileInput.files[0]);
            fileInput.value = "";
        });

        uploadZone.addEventListener("dragover", e => { e.preventDefault(); uploadZone.classList.add("drag-over"); });
        uploadZone.addEventListener("dragleave", () => uploadZone.classList.remove("drag-over"));
        uploadZone.addEventListener("drop", e => {
            e.preventDefault();
            uploadZone.classList.remove("drag-over");
            const file = e.dataTransfer && e.dataTransfer.files[0];
            if (file) uploadFile(file);
        });
    }

    if (yearSelect) {
        yearSelect.addEventListener("change", () => {
            currentYear = parseInt(yearSelect.value, 10);
            buildQuickChips();
        });
    }

    clearHistoryBtn.addEventListener("click", async () => {
        await fetch("/api/clear-history", { method: "POST" });
        messagesEl.innerHTML = "";
    });

    refreshBtn.addEventListener("click", () => {
        fetchEmployees(); fetchFiles(); fetchYears();
    });

    newChatBtn.addEventListener("click", async () => {
        await fetch("/api/clear-history", { method: "POST" });
        messagesEl.innerHTML = "";
        showWelcomeCard();
    });

    hamburgerBtn.addEventListener("click", () => {
        sidebar.classList.add("open");
        overlay.classList.add("visible");
    });

    overlay.addEventListener("click", closeSidebar);

    function closeSidebar() {
        sidebar.classList.remove("open");
        overlay.classList.remove("visible");
    }

    // Swipe-left to close sidebar on mobile
    let touchStartX = 0;
    sidebar.addEventListener("touchstart", e => { touchStartX = e.touches[0].clientX; }, { passive: true });
    sidebar.addEventListener("touchend", e => {
        if (e.changedTouches[0].clientX - touchStartX < -40) closeSidebar();
    }, { passive: true });

    // Collapsible sections — "fileListContent" starts collapsed so employees are visible on load
    document.querySelectorAll(".collapsible").forEach(header => {
        const target = header.dataset["target"];
        if (!target) return;
        const content = document.getElementById(target);
        if (!content) return;
        if (target === "fileListContent") {
            content.classList.add("collapsed");
        } else {
            header.classList.add("open");
        }
        header.addEventListener("click", () => {
            const isOpen = header.classList.toggle("open");
            content.classList.toggle("collapsed", !isOpen);
        });
    });

    statusDot.classList.add("online");

})();

/* ── Mobile topbar adaptation — New Chat button ─────────────────────────── */
(function () {
    var newChatBtnEl = document.getElementById('newChatBtn');
    if (!newChatBtnEl) return;
    var textEl = newChatBtnEl.querySelector('.topbar-btn-text');
    var iconEl = newChatBtnEl.querySelector('.topbar-btn-icon');
    function adaptTopbar() {
        var isMobile = window.innerWidth <= 768;
        if (textEl) textEl.style.display = isMobile ? 'none' : '';
        if (iconEl) iconEl.style.display = isMobile ? '' : 'none';
    }
    adaptTopbar();
    window.addEventListener('resize', adaptTopbar);
})();

/* ── Reset Password modal ────────────────────────────────────────────────── */
(function () {
    "use strict";

    var backdrop     = document.getElementById("rpBackdrop");
    var openBtn      = document.getElementById("resetPasswordBtn");
    var closeBtn     = document.getElementById("rpCloseBtn");
    var cancelBtn    = document.getElementById("rpCancelBtn");
    var submitBtn    = document.getElementById("rpSubmitBtn");
    var currentInput = document.getElementById("rpCurrentPwd");
    var newInput     = document.getElementById("rpNewPwd");
    var msgEl        = document.getElementById("rpMsg");

    // Nothing to wire if the modal isn't on this page.
    if (!backdrop || !openBtn) return;

    function openModal() {
        clearForm();
        backdrop.classList.add("rp-open");
        currentInput.focus();
    }

    function closeModal() {
        backdrop.classList.remove("rp-open");
        clearForm();
    }

    function clearForm() {
        currentInput.value = "";
        newInput.value     = "";
        setMsg("", "");
        submitBtn.disabled = false;
        // Reset eye buttons back to password type.
        [currentInput, newInput].forEach(function (inp) {
            inp.type = "password";
        });
    }

    function setMsg(text, type) {
        msgEl.textContent  = text;
        msgEl.className    = "rp-msg" + (type ? " rp-" + type : "");
    }

    // ── Eye-toggle ─────────────────────────────────────────────────────────
    backdrop.querySelectorAll(".rp-eye").forEach(function (btn) {
        btn.addEventListener("click", function () {
            var targetId = btn.getAttribute("data-target");
            var input    = document.getElementById(targetId);
            if (!input) return;
            input.type = (input.type === "password") ? "text" : "password";
        });
    });

    // ── Backdrop click (click outside modal closes it) ─────────────────────
    backdrop.addEventListener("click", function (e) {
        if (e.target === backdrop) closeModal();
    });

    // ── Keyboard: Escape closes the modal ──────────────────────────────────
    document.addEventListener("keydown", function (e) {
        if (e.key === "Escape" && backdrop.classList.contains("rp-open")) {
            closeModal();
        }
    });

    openBtn.addEventListener("click",  openModal);
    closeBtn.addEventListener("click", closeModal);
    cancelBtn.addEventListener("click", closeModal);

    // ── Submit ─────────────────────────────────────────────────────────────
    submitBtn.addEventListener("click", async function () {
        var current = currentInput.value.trim();
        var newPwd  = newInput.value.trim();

        if (!current || !newPwd) {
            setMsg("Please fill in both fields.", "error");
            return;
        }

        submitBtn.disabled = true;
        setMsg("", "");

        try {
            var res = await fetch("/api/change-password", {
                method:  "POST",
                headers: { "Content-Type": "application/json" },
                body:    JSON.stringify({ currentPassword: current, newPassword: newPwd })
            });
            var data = await res.json();

            if (data.success) {
                setMsg("Password updated successfully!", "success");
                currentInput.value = "";
                newInput.value     = "";
                submitBtn.disabled = false;
            } else {
                setMsg(data.error || "Failed to update password.", "error");
                submitBtn.disabled = false;
            }
        } catch (err) {
            setMsg("Network error. Please try again.", "error");
            submitBtn.disabled = false;
        }
    });

})();
