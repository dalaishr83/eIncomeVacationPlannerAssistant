/**
 * holiday_agent_admin.js — Admin page SPA logic.
 * Extends the base functionality with file deletion support.
 */
(function () {
    "use strict";

    // ── State ──────────────────────────────────────────────────────────────────
    let employees = [];
    let currentYear = new Date().getFullYear();
    let isThinking = false;

    // ── DOM refs (null-safe — admin page may omit some elements) ───────────────
    const messagesEl      = document.getElementById("messages");
    const welcomeCard     = document.getElementById("welcomeCard");
    const messageInput    = document.getElementById("messageInput");
    const sendBtn         = document.getElementById("sendBtn");
    const fileInput       = document.getElementById("fileInput");
    const uploadZone      = document.getElementById("uploadZone");
    const fileList        = document.getElementById("fileList");
    const employeeList    = document.getElementById("employeeList");
    const quickChips      = document.getElementById("quickChips");
    const clearHistoryBtn = document.getElementById("clearHistoryBtn");
    const refreshBtn      = document.getElementById("refreshBtn");
    const newChatBtn      = document.getElementById("newChatBtn");
    const hamburgerBtn    = document.getElementById("hamburgerBtn");
    const sidebar         = document.getElementById("sidebar");
    const overlay         = document.getElementById("overlay");
    const statusDot       = document.getElementById("statusDot");
    const topbarSubtitle      = document.getElementById("topbarSubtitle");
    const provisionMasterBtn  = document.getElementById("provisionMasterBtn");

    // ── Boot sequence ──────────────────────────────────────────────────────────
    Promise.all([fetchEmployees(), fetchFiles(), fetchYears()]).catch(function () {});

    // ── Chat ───────────────────────────────────────────────────────────────────

    async function sendMessage() {
        var msg = messageInput.value.trim();
        if (!msg || isThinking) return;

        // Show a human-readable label instead of the raw form-submission prefix
        var displayMsg = msg.startsWith("__VACATION_FORM__:")
            ? "✅ Form submitted"
            : msg;
        appendMessage(displayMsg, "user");
        messageInput.value = "";
        autoResizeTextarea();
        setThinking(true);
        var thinking = appendThinking();
        try {
            var res = await fetch("/api/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ message: msg })
            });
            var data = await res.json();
            thinking.remove();
            if (data.error) {
                appendMessage("⚠️ " + data.error, "bot");
            } else if (data.type === "vacation_form") {
                // Render the inline vacation form widget instead of a text bubble.
                hideWelcomeCard();
                var formPayload = null;
                try { formPayload = JSON.parse(data.reply); } catch (e) {}
                if (formPayload) {
                    var msgEl = appendMessage("", "bot", "vacation_prompt");
                    renderVacationForm(formPayload, msgEl);
                } else {
                    appendMessage("Could not load the vacation form. Please type 'cancel' to abort.", "bot");
                }
            } else if (data.tableData && data.tableData.length > 0) {
                // Table-only mode: skip the LLM text bubble and show just the table.
                hideWelcomeCard();
                var tableRow = document.createElement("div");
                tableRow.className = "message bot";
                tableRow.appendChild(makeBotAvatar());
                messagesEl.appendChild(tableRow);
                appendLeaveTable(tableRow, data.tableData);
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

    // ── File upload ────────────────────────────────────────────────────────────

    async function uploadFile(file) {
        if (!file.name.toLowerCase().endsWith(".xlsx")) {
            appendMessage("⚠️ Only .xlsx files are supported.", "bot");
            return;
        }
        var thinking = appendMessage("Uploading and parsing file…", "bot");
        setThinking(true);
        var formData = new FormData();
        formData.append("file", file);
        try {
            var res = await fetch("/api/upload", { method: "POST", body: formData });
            var data = await res.json();
            thinking.remove();
            setThinking(false);
            if (data.error) { appendMessage("⚠️ " + data.error, "bot"); return; }
            employees = data.employees || [];
            renderEmployees();
            renderFiles(data.files || []);
            appendMessage("✅ " + data.message + "\n\nYou can now ask questions about employee leave data.", "bot");
        } catch (e) {
            thinking.remove();
            setThinking(false);
            appendMessage("⚠️ Upload failed. Please try again.", "bot");
        }
    }

    // ── File deletion (admin only) ─────────────────────────────────────────────

    async function deleteFile(filename) {
        if (!confirm("Delete '" + filename + "'? This will remove the master, working, and upload copies.")) return;
        try {
            var res = await fetch("/api/admin/files", {
                method: "DELETE",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ filename: filename })
            });
            var data = await res.json();
            if (data.error) {
                appendMessage("⚠️ " + data.error, "bot");
            } else {
                renderFiles(data.files || []);
                employees = [];
                renderEmployees();
                appendMessage("✅ " + data.message, "bot");
            }
        } catch (e) {
            appendMessage("⚠️ Delete failed. Please try again.", "bot");
        }
    }

    // ── Provision next-year master file (admin only) ───────────────────────────

    async function provisionNextYear() {
        var targetYear;
        try {
            var yr = await fetch("/api/admin/provision-next-year/target-year");
            var yrData = await yr.json();
            targetYear = yrData.targetYear;
        } catch (e) {
            appendMessage("⚠️ Could not determine target year. Please try again.", "bot");
            return;
        }
        if (!confirm("Provision the Master Excel file for " + targetYear + "?\n\nThis will copy the template and replace all YEAR placeholders with " + targetYear + ".")) return;
        setThinking(true);
        try {
            var res = await fetch("/api/admin/provision-next-year", { method: "POST" });
            var data = await res.json();
            if (data.error) {
                appendMessage("⚠️ " + data.error, "bot");
            } else {
                renderFiles(data.files || []);
                appendMessage("✅ " + data.message, "bot");
            }
        } catch (e) {
            appendMessage("⚠️ Provisioning failed. Please try again.", "bot");
        } finally {
            setThinking(false);
        }
    }

    // ── API helpers ────────────────────────────────────────────────────────────

    async function fetchEmployees() {
        try {
            var res = await fetch("/api/employees");
            var data = await res.json();
            employees = data.employees || [];
            renderEmployees();
            buildQuickChips();
        } catch (e) {}
    }

    async function fetchYears() {
        try {
            var res = await fetch("/api/years");
            var data = await res.json();
            var years = data.years || [];
            if (years.length > 0) {
                currentYear = years[0];
            }
            buildQuickChips();
        } catch (e) {}
    }

    async function fetchFiles() {
        try {
            var res = await fetch("/api/files");
            var data = await res.json();
            renderFiles(data.files || []);
        } catch (e) {}
    }

    async function switchFile(path) {
        setThinking(true);
        try {
            var res = await fetch("/api/switch-file", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ path: path })
            });
            var data = await res.json();
            if (data.error) { appendMessage("⚠️ " + data.error, "bot"); return; }
            employees = data.employees || [];
            renderEmployees();
            renderFiles(data.files || []);
            appendMessage("Switched to " + path.split(/[/\\]/).pop() + ". " + employees.length + " employees loaded.", "bot");
        } catch (e) {
            appendMessage("⚠️ Failed to switch file.", "bot");
        } finally {
            setThinking(false);
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    function renderEmployees() {
        if (topbarSubtitle) {
            topbarSubtitle.textContent = employees.length > 0
                ? "Admin — " + employees.length + " employee(s) loaded"
                : "Admin — No file loaded";
        }
        if (!employeeList) return;
        employeeList.innerHTML = "";
        employees.forEach(function (name) {
            var li = document.createElement("li");
            var dot = document.createElement("span");
            dot.className = "emp-dot";
            li.appendChild(dot);
            var label = document.createElement("span");
            label.textContent = name;
            li.appendChild(label);
            li.title = name;
            employeeList.appendChild(li);
        });
    }

    function renderFiles(files) {
        if (!fileList) return;
        fileList.innerHTML = "";
        files.forEach(function (f) {
            var li = document.createElement("li");
            li.style.display = "flex";
            li.style.alignItems = "center";

            var nameSpan = document.createElement("span");
            nameSpan.className = "file-name";
            nameSpan.textContent = f.name;
            nameSpan.style.cursor = "pointer";
            nameSpan.title = f.path;
            nameSpan.addEventListener("click", function () { switchFile(f.path); });
            li.appendChild(nameSpan);

            if (f.active) {
                li.classList.add("active");
                var badge = document.createElement("span");
                badge.className = "file-badge";
                badge.textContent = "Active";
                li.appendChild(badge);
            }

            // Delete button (admin only)
            var delBtn = document.createElement("button");
            delBtn.className = "file-delete-btn";
            delBtn.title = "Delete " + f.name;
            delBtn.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>';
            delBtn.addEventListener("click", function (e) {
                e.stopPropagation();
                deleteFile(f.name);
            });
            li.appendChild(delBtn);

            fileList.appendChild(li);
        });
    }

    // ── Message rendering (shared) ─────────────────────────────────────────────

    function hideWelcomeCard() {
        if (welcomeCard && welcomeCard.parentNode) welcomeCard.remove();
    }

    var BOT_AVATAR_SVG =
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
        var av = document.createElement("div");
        av.className = "bot-avatar";
        av.innerHTML = BOT_AVATAR_SVG;
        return av;
    }

    function appendMessage(text, role, type) {
        type = type || "text";
        hideWelcomeCard();
        var div = document.createElement("div");
        div.className = "message " + role;
        if (type === "vacation_prompt") div.classList.add("vacation-prompt");
        if (role === "bot") div.appendChild(makeBotAvatar());
        var bubble = document.createElement("div");
        bubble.className = "bubble";
        if (type === "report") {
            var match = text.match(/report-file:\s*(.+)/i);
            var display = text.replace(/\nreport-file:[^\n]*/i, "").trim();
            bubble.innerHTML = renderMarkdown(display);
            if (match) {
                var fname = match[1].trim().split(/[/\\]/).pop() || "";
                var link = document.createElement("a");
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
        if (topbarSubtitle) topbarSubtitle.textContent = "Thinking…";
        var div = document.createElement("div");
        div.className = "message bot";
        div.id = "thinkingRow";
        div.appendChild(makeBotAvatar());
        var dots = document.createElement("div");
        dots.className = "thinking-dots";
        dots.innerHTML = "<span></span><span></span><span></span>";
        div.appendChild(dots);
        messagesEl.appendChild(div);
        messagesEl.scrollTop = messagesEl.scrollHeight;
        return div;
    }

    function renderMarkdown(text) {
        var html = text.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
        html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
        html = html.replace(/\n/g, "<br>");
        return html;
    }

    function setThinking(state) {
        isThinking = state;
        if (sendBtn) sendBtn.disabled = state;
        if (messageInput) messageInput.disabled = state;
        if (!state && topbarSubtitle) {
            topbarSubtitle.textContent = employees.length > 0
                ? "Admin — " + employees.length + " employee(s) loaded"
                : "Admin — No file loaded";
        }
    }

    function autoResizeTextarea() {
        if (!messageInput) return;
        messageInput.style.height = "auto";
        messageInput.style.height = Math.min(messageInput.scrollHeight, 160) + "px";
    }

    // ── Event listeners ────────────────────────────────────────────────────────

    if (sendBtn) sendBtn.addEventListener("click", sendMessage);

    if (messageInput) {
        messageInput.addEventListener("keydown", function (e) {
            if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
        });
        messageInput.addEventListener("input", autoResizeTextarea);
    }

    if (uploadZone && fileInput) {
        uploadZone.addEventListener("click", function () { fileInput.click(); });
        fileInput.addEventListener("change", function () {
            if (fileInput.files && fileInput.files[0]) uploadFile(fileInput.files[0]);
            fileInput.value = "";
        });
        uploadZone.addEventListener("dragover", function (e) {
            e.preventDefault(); uploadZone.classList.add("drag-over");
        });
        uploadZone.addEventListener("dragleave", function () {
            uploadZone.classList.remove("drag-over");
        });
        uploadZone.addEventListener("drop", function (e) {
            e.preventDefault();
            uploadZone.classList.remove("drag-over");
            var file = e.dataTransfer && e.dataTransfer.files[0];
            if (file) uploadFile(file);
        });
    }

    if (clearHistoryBtn) {
        clearHistoryBtn.addEventListener("click", async function () {
            await fetch("/api/clear-history", { method: "POST" });
            messagesEl.innerHTML = "";
        });
    }

    if (refreshBtn) {
        refreshBtn.addEventListener("click", function () {
            fetchEmployees(); fetchFiles(); fetchYears();
        });
    }

    if (provisionMasterBtn) {
        provisionMasterBtn.addEventListener("click", provisionNextYear);
    }

    if (newChatBtn) {
        newChatBtn.addEventListener("click", async function () {
            await fetch("/api/clear-history", { method: "POST" });
            messagesEl.innerHTML = "";
        });
    }

    if (hamburgerBtn && sidebar && overlay) {
        hamburgerBtn.addEventListener("click", function () {
            sidebar.classList.add("open");
            overlay.classList.add("visible");
        });
        overlay.addEventListener("click", function () {
            sidebar.classList.remove("open");
            overlay.classList.remove("visible");
        });
        var touchStartX = 0;
        sidebar.addEventListener("touchstart", function (e) {
            touchStartX = e.touches[0].clientX;
        }, { passive: true });
        sidebar.addEventListener("touchend", function (e) {
            if (e.changedTouches[0].clientX - touchStartX < -40) {
                sidebar.classList.remove("open");
                overlay.classList.remove("visible");
            }
        }, { passive: true });
    }

    // Collapsible sections
    document.querySelectorAll(".collapsible").forEach(function (header) {
        var target = header.dataset["target"];
        if (!target) return;
        var content = document.getElementById(target);
        if (!content) return;
        if (target === "fileListContent") {
            header.classList.add("open");   // keep files expanded for admin
        } else {
            header.classList.add("open");
        }
        header.addEventListener("click", function () {
            var isOpen = header.classList.toggle("open");
            content.classList.toggle("collapsed", !isOpen);
        });
    });

    if (statusDot) statusDot.classList.add("online");

    // ── HTML escaping ──────────────────────────────────────────────────────────

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g,  "&amp;")
            .replace(/</g,  "&lt;")
            .replace(/>/g,  "&gt;")
            .replace(/"/g,  "&quot;")
            .replace(/'/g,  "&#39;");
    }

    // ── Leave table ────────────────────────────────────────────────────────────

    function appendLeaveTable(msgEl, tableData) {
        var wrap = document.createElement("div");
        wrap.className = "leave-table-wrap";

        var table = document.createElement("table");
        table.className = "leave-table";

        var thead = document.createElement("thead");
        thead.innerHTML =
            "<tr>" +
            "<th>Employee Name</th>" +
            "<th>Month</th>" +
            "<th class=\"leave-table-num\">Total Vacation</th>" +
            "</tr>";
        table.appendChild(thead);

        var tbody = document.createElement("tbody");
        tableData.forEach(function (row) {
            var tr = document.createElement("tr");
            tr.innerHTML =
                "<td>" + escapeHtml(row.employee_name || "") + "</td>" +
                "<td>" + escapeHtml(row.month || "") + "</td>" +
                "<td class=\"leave-table-num\">" + (row.total_vacation != null ? row.total_vacation : "") + "</td>";
            tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        wrap.appendChild(table);

        var copyBtn = document.createElement("button");
        copyBtn.className = "leave-table-copy";
        copyBtn.textContent = "Copy table";
        copyBtn.addEventListener("click", function () {
            var lines = ["Employee Name\tMonth\tTotal Vacation"];
            tableData.forEach(function (row) {
                lines.push(
                    [row.employee_name || "", row.month || "",
                     row.total_vacation != null ? row.total_vacation : ""].join("\t")
                );
            });
            var text = lines.join("\n");

            function onSuccess() {
                copyBtn.textContent = "Copied!";
                setTimeout(function () { copyBtn.textContent = "Copy table"; }, 2000);
            }
            function onFailure() {
                copyBtn.textContent = "Copy failed";
                setTimeout(function () { copyBtn.textContent = "Copy table"; }, 2000);
            }

            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(onSuccess).catch(onFailure);
            } else {
                try {
                    var ta = document.createElement("textarea");
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

    function renderVacationForm(payload, msgEl) {
        var isAdd   = payload.wizardType === "add";
        var empName = payload.employeeName || "";
        var types   = Array.isArray(payload.types) ? payload.types : [];

        // minDate = today (no past dates); maxDate = 6 months from today
        var now = new Date();
        var minDate = now.getFullYear() + "-" +
            String(now.getMonth() + 1).padStart(2, "0") + "-" +
            String(now.getDate()).padStart(2, "0");
        var maxDateObj = new Date(now.getFullYear(), now.getMonth() + 6, now.getDate());
        var maxDate = maxDateObj.getFullYear() + "-" +
            String(maxDateObj.getMonth() + 1).padStart(2, "0") + "-" +
            String(maxDateObj.getDate()).padStart(2, "0");

        var bubble = msgEl.querySelector(".bubble");
        if (!bubble) return;

        var form = document.createElement("form");
        form.className = "vac-form";
        form.noValidate = true;

        var title = document.createElement("div");
        title.className = "vac-form-title";
        title.textContent = (isAdd ? "Add vacation" : "Delete vacation") + " for " + empName;
        form.appendChild(title);

        var radioInputs = [];
        if (isAdd && types.length > 0) {
            var typeLabel = document.createElement("div");
            typeLabel.className = "vac-form-section-label";
            typeLabel.textContent = "Vacation type";
            form.appendChild(typeLabel);

            var radioGroup = document.createElement("div");
            radioGroup.className = "vac-form-radio-group";

            types.forEach(function (t, idx) {
                var lbl = document.createElement("label");
                lbl.className = "vac-form-radio-label";

                var radio = document.createElement("input");
                radio.type  = "radio";
                radio.name  = "vac_type";
                radio.value = t.code;
                if (idx === 0) radio.checked = true;

                var text = document.createTextNode(t.label);
                var codePill = document.createElement("span");
                codePill.className = "vac-form-radio-code";
                codePill.textContent = t.code;

                lbl.appendChild(radio);
                lbl.appendChild(text);
                lbl.appendChild(codePill);

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

        if (!isAdd) {
            var warn = document.createElement("div");
            warn.className = "vac-form-warning";
            warn.innerHTML =
                '<svg class="vac-form-warning-icon" width="15" height="15" viewBox="0 0 20 20" fill="none">' +
                '<path d="M10 2L2 17h16L10 2z" stroke="#92400e" stroke-width="1.5" stroke-linejoin="round" fill="#fde68a"/>' +
                '<path d="M10 8v4M10 13.5v.5" stroke="#92400e" stroke-width="1.5" stroke-linecap="round"/>' +
                '</svg>' +
                '<span>Vacation entries in the selected date range will be <strong>permanently removed</strong>. This action cannot be undone.</span>';
            form.appendChild(warn);
        }

        var dateLabel = document.createElement("div");
        dateLabel.className = "vac-form-section-label";
        dateLabel.textContent = isAdd ? "Date range" : "Date range to delete";
        form.appendChild(dateLabel);

        var datesRow = document.createElement("div");
        datesRow.className = "vac-form-dates";

        function makeDateField(labelText, inputId) {
            var field = document.createElement("div");
            field.className = "vac-form-date-field";
            var lbl = document.createElement("div");
            lbl.className = "vac-form-date-label";
            lbl.textContent = labelText;
            var inp = document.createElement("input");
            inp.type  = "date";
            inp.id    = inputId;
            inp.className = "vac-form-date-input";
            inp.min   = minDate;
            inp.max   = maxDate;
            inp.required = true;
            field.appendChild(lbl);
            field.appendChild(inp);
            return { field: field, inp: inp };
        }

        var startResult = makeDateField("Start date", "vac_start_" + Date.now());
        var endResult   = makeDateField("End date",   "vac_end_"   + (Date.now() + 1));
        var startInput  = startResult.inp;
        var endInput    = endResult.inp;
        datesRow.appendChild(startResult.field);
        datesRow.appendChild(endResult.field);
        form.appendChild(datesRow);

        startInput.addEventListener("change", function () {
            if (endInput.value && endInput.value < startInput.value) {
                endInput.value = startInput.value;
            }
            endInput.min = startInput.value || minDate;
            endInput.max = maxDate;
        });

        var errDiv = document.createElement("div");
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

        var submitRow = document.createElement("div");
        submitRow.className = "vac-form-submit-row";

        var cancelBtn = document.createElement("button");
        cancelBtn.type = "button";
        cancelBtn.className = "vac-form-cancel-btn";
        cancelBtn.textContent = "Cancel";

        var submitBtn = document.createElement("button");
        submitBtn.type = "submit";
        submitBtn.className = "vac-form-submit-btn" + (isAdd ? "" : " vac-form-submit-btn--danger");
        submitBtn.textContent = isAdd ? "Add vacation" : "Delete vacation";

        submitRow.appendChild(cancelBtn);
        submitRow.appendChild(submitBtn);
        form.appendChild(submitRow);

        cancelBtn.addEventListener("click", function () {
            disableForm();
            messageInput.value = "cancel";
            sendMessage();
        });

        form.addEventListener("submit", function (e) {
            e.preventDefault();
            clearError();

            var selectedCode = "";
            if (isAdd) {
                var checked = form.querySelector("input[name='vac_type']:checked");
                if (!checked) { showError("Please select a vacation type."); return; }
                selectedCode = checked.value;
            } else {
                selectedCode = "DELETE";
            }

            var startVal = startInput.value;
            var endVal   = endInput.value;
            if (!startVal) { showError("Please select a start date."); return; }
            if (!endVal)   { showError("Please select an end date."); return; }
            if (endVal < startVal) { showError("Start date cannot be greater than end date."); return; }
            if (startVal < minDate) {
                showError("Past dates are not allowed for start date."); return;
            }
            if (startVal > maxDate) {
                showError("Start date cannot be more than 6 months in the future."); return;
            }
            if (endVal < minDate) {
                showError("Past dates are not allowed for end date."); return;
            }
            if (endVal > maxDate) {
                showError("End date cannot be more than 6 months in the future."); return;
            }

            disableForm();

            var encoded = "__VACATION_FORM__:" + selectedCode + "|" + startVal + "|" + endVal;
            messageInput.value = encoded;
            sendMessage();
        });

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

    function buildQuickChips() {
        if (!quickChips) return;
        quickChips.innerHTML = "";
        var messagesEl = document.getElementById("messages");
        var loginUsername = (messagesEl
            ? messagesEl.getAttribute("data-login-username")
            : null) || employees[0] || "me";
        var templates = [
            "Who is on leave on 02 March {year}?",
            "Who are on leave between 31 Aug and 7 Sep {year}?",
            "How many leave days does {name} have from April to June {year}?",
            "Show all employees' leave totals for {year}",
            "Generate leave report for {name} in {year}",
            "Add vacation for {name}",
            "Delete vacation for {name}",
        ];
        templates.forEach(function (tpl) {
            var label = tpl.replace(/\{name\}/g, loginUsername).replace(/\{year\}/g, String(currentYear));
            var chip = document.createElement("button");
            chip.className = "chip";
            chip.textContent = label;
            chip.addEventListener("click", function () {
                if (messageInput) {
                    messageInput.value = label;
                    messageInput.focus();
                }
            });
            quickChips.appendChild(chip);
        });
    }

})();
