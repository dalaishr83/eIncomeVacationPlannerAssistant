/**
 * holiday_agent_file_mgmt.js — File Management page SPA logic.
 *
 * Responsibilities:
 *  - Toggle the active upload type via radio buttons (4 options).
 *  - Handle drop-zone drag-and-drop and browse-to-upload interactions.
 *  - Dispatch the file to the correct backend endpoint:
 *      • master          → POST /api/upload                (existing workflow, unchanged)
 *      • india-holiday   → POST /api/admin/holiday-upload  country=india
 *      • denmark-holiday → POST /api/admin/holiday-upload  country=denmark
 *      • romania-holiday → POST /api/admin/holiday-upload  country=romania
 *  - Render the available master-file list with switch and delete actions,
 *    reusing the renderFiles / deleteFile / switchFile helpers from
 *    holiday_agent_admin.js which is loaded before this script.
 */
(function () {
    "use strict";

    // ── DOM refs ───────────────────────────────────────────────────────────────
    var dropZone   = document.getElementById("fmDropZone");
    var fileInput  = document.getElementById("fmFileInput");
    var browseBtn  = document.getElementById("fmBrowseBtn");
    var alertEl    = document.getElementById("fmAlert");
    var fileListEl = document.getElementById("fmFileList");

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Returns the currently-selected upload type: "master" or "holiday". */
    function selectedType() {
        var checked = document.querySelector("input[name='uploadType']:checked");
        return checked ? checked.value : "master";
    }

    /** Show a status message in the alert bar. */
    function showAlert(text, type) {
        alertEl.textContent = text;
        alertEl.className   = "fm-alert " + type;
    }

    function clearAlert() {
        alertEl.textContent = "";
        alertEl.className   = "fm-alert";
    }

    // ── Upload type visual highlight ───────────────────────────────────────────

    document.querySelectorAll("input[name='uploadType']").forEach(function (radio) {
        radio.addEventListener("change", function () {
            clearAlert();
            // :has() CSS handles visual highlighting; nothing extra needed here.
        });
    });

    // ── Drop zone interactions ─────────────────────────────────────────────────

    if (browseBtn && fileInput) {
        browseBtn.addEventListener("click", function (e) {
            e.stopPropagation();
            fileInput.click();
        });
    }

    if (dropZone && fileInput) {
        dropZone.addEventListener("click", function () { fileInput.click(); });

        dropZone.addEventListener("dragover", function (e) {
            e.preventDefault();
            dropZone.classList.add("drag-over");
        });
        dropZone.addEventListener("dragleave", function () {
            dropZone.classList.remove("drag-over");
        });
        dropZone.addEventListener("drop", function (e) {
            e.preventDefault();
            dropZone.classList.remove("drag-over");
            var file = e.dataTransfer && e.dataTransfer.files[0];
            if (file) handleFile(file);
        });

        fileInput.addEventListener("change", function () {
            if (fileInput.files && fileInput.files[0]) {
                handleFile(fileInput.files[0]);
            }
            fileInput.value = "";
        });
    }

    // ── Core upload dispatcher ─────────────────────────────────────────────────

    function handleFile(file) {
        if (!file.name.toLowerCase().endsWith(".xlsx")) {
            showAlert("⚠️ Only .xlsx files are supported.", "error");
            return;
        }
        var type = selectedType();
        if (type === "india-holiday") {
            uploadHolidayMaster(file, "india");
        } else if (type === "denmark-holiday") {
            uploadHolidayMaster(file, "denmark");
        } else if (type === "romania-holiday") {
            uploadHolidayMaster(file, "romania");
        } else {
            uploadMasterFile(file);
        }
    }

    /**
     * Upload a Master File.
     * Delegates entirely to the existing POST /api/upload endpoint.
     * On success the sidebar file list is refreshed via the shared renderFiles()
     * function that holiday_agent_admin.js exposes through window.__adminRenderFiles.
     */
    function uploadMasterFile(file) {
        showAlert("Uploading master file…", "info");
        var formData = new FormData();
        formData.append("file", file);
        fetch("/api/upload", { method: "POST", body: formData })
            .then(function (res) { return res.json(); })
            .then(function (data) {
                if (data.error) {
                    showAlert("⚠️ " + data.error, "error");
                } else {
                    showAlert("✅ " + data.message, "success");
                    refreshFileList(data.files || null);
                }
            })
            .catch(function () {
                showAlert("⚠️ Upload failed. Please try again.", "error");
            });
    }

    /**
     * Upload a country-specific holiday reference file.
     * @param {File}   file    — the .xlsx file chosen by the user
     * @param {string} country — "india" | "denmark" | "romania"
     *
     * Sends country to the backend so it can generate the canonical filename:
     *   india   → India-holiday-<year>.xlsx
     *   denmark → Denmark-holiday-<year>.xlsx
     *   romania → Romania-holiday-<year>.xlsx
     */
    function uploadHolidayMaster(file, country) {
        showAlert("Uploading holiday file…", "info");
        var formData = new FormData();
        formData.append("file", file);
        formData.append("country", country);
        fetch("/api/admin/holiday-upload", { method: "POST", body: formData })
            .then(function (res) { return res.json(); })
            .then(function (data) {
                if (data.error) {
                    showAlert("⚠️ " + data.error, "error");
                } else {
                    showAlert("✅ " + data.message, "success");
                }
            })
            .catch(function () {
                showAlert("⚠️ Upload failed. Please try again.", "error");
            });
    }

    // ── File list rendering ────────────────────────────────────────────────────

    /**
     * Renders the available master-file list.
     * If `files` is null, fetches the list from /api/files first.
     */
    function refreshFileList(files) {
        if (files !== null) {
            renderList(files);
            return;
        }
        fetch("/api/files")
            .then(function (r) { return r.json(); })
            .then(function (data) { renderList(data.files || []); })
            .catch(function () {});
    }

    function renderList(files) {
        // Also sync the sidebar file list via the shared helper from admin.js
        if (typeof window.__adminRenderFiles === "function") {
            window.__adminRenderFiles(files);
        }

        if (!fileListEl) return;
        fileListEl.innerHTML = "";

        if (!files || files.length === 0) {
            var empty = document.createElement("li");
            empty.className = "fm-file-empty";
            empty.textContent = "No master files found.";
            fileListEl.appendChild(empty);
            return;
        }

        files.forEach(function (f) {
            var li = document.createElement("li");
            li.className = "fm-file-item";

            var nameSpan = document.createElement("span");
            nameSpan.className = "fm-file-name";
            nameSpan.textContent = f.name;
            nameSpan.title = f.path;
            nameSpan.addEventListener("click", function () { switchMasterFile(f.path); });
            li.appendChild(nameSpan);

            if (f.active) {
                var badge = document.createElement("span");
                badge.className = "fm-file-badge";
                badge.textContent = "Active";
                li.appendChild(badge);
            }

            var viewBtn = document.createElement("button");
            viewBtn.className = "fm-file-view-btn";
            viewBtn.title = "View " + f.name;
            viewBtn.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
            viewBtn.addEventListener("click", function (e) {
                e.stopPropagation();
                window.open(
                    "/admin/master-file-view?filename=" + encodeURIComponent(f.name),
                    "_blank",
                    "width=1100,height=700,resizable=yes,scrollbars=yes,noopener"
                );
            });
            li.appendChild(viewBtn);


            var delBtn = document.createElement("button");
            delBtn.className = "fm-file-delete-btn";
            delBtn.title = "Delete " + f.name;
            delBtn.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>';
            delBtn.addEventListener("click", function (e) {
                e.stopPropagation();
                deleteMasterFile(f.name);
            });
            li.appendChild(delBtn);

            fileListEl.appendChild(li);
        });
    }

    function switchMasterFile(path) {
        fetch("/api/switch-file", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ path: path })
        })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data.error) {
                showAlert("⚠️ " + data.error, "error");
            } else {
                showAlert("✅ Switched to " + path.split(/[/\\]/).pop(), "success");
                refreshFileList(data.files || null);
            }
        })
        .catch(function () {
            showAlert("⚠️ Failed to switch file.", "error");
        });
    }

    function deleteMasterFile(filename) {
        if (!confirm("Delete '" + filename + "'?\n\nThis will remove the master, working, and upload copies.")) return;
        fetch("/api/admin/files", {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ filename: filename })
        })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data.error) {
                showAlert("⚠️ " + data.error, "error");
            } else {
                showAlert("✅ " + data.message, "success");
                refreshFileList(data.files || null);
            }
        })
        .catch(function () {
            showAlert("⚠️ Delete failed. Please try again.", "error");
        });
    }

    // ── Boot: load the file list on page open ──────────────────────────────────
    refreshFileList(null);

})();
