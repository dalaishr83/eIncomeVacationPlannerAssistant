/**
 * holiday_agent_sync_holiday.js — Sync Master Holiday page logic.
 *
 * Handles:
 *   Card 4 — Sync Indian Holiday  (#holidayFileList, #holidaySyncAlert)
 *   Card 5 — Mapped Employee City (#emcTableBody, #emcSaveBtn, #emcAlert)
 *
 * The CSS for both cards lives in holiday_agent_settings.css (shared).
 * The collapsible card behaviour is driven by the sc-header click handler below.
 */
(function () {
    'use strict';

    // ── Collapsible cards ─────────────────────────────────────────────────────
    document.querySelectorAll('.sc-header').forEach(function (header) {
        header.addEventListener('click', function () {
            var card = document.getElementById(header.getAttribute('data-target'));
            if (!card) return;
            card.classList.toggle('open');
        });
    });

    // ── Card 4: Sync Indian Holiday ───────────────────────────────────────────

    var holidayFileList = document.getElementById('holidayFileList');

    // True while any sync or delete operation is in progress.
    // Prevents concurrent operations across all rows.
    var syncInProgress = false;

    function freezeFileList() {
        syncInProgress = true;
        if (!holidayFileList) return;
        holidayFileList.querySelectorAll('.hf-sync-btn, .hf-delete-btn').forEach(function (btn) {
            btn.disabled = true;
        });
    }

    function thawFileList() {
        syncInProgress = false;
        if (!holidayFileList) return;
        holidayFileList.querySelectorAll('.hf-sync-btn, .hf-delete-btn').forEach(function (btn) {
            btn.disabled = false;
        });
    }

    // Inject a shared alert div above the file list (once, on page load)
    var holidaySyncAlert = (function () {
        var el = document.getElementById('holidaySyncAlert');
        if (!el && holidayFileList && holidayFileList.parentNode) {
            el = document.createElement('div');
            el.id = 'holidaySyncAlert';
            el.className = 'alert';
            el.style.display = 'none';
            holidayFileList.parentNode.insertBefore(el, holidayFileList);
        }
        return el;
    }());

    function showHolidaySyncAlert(msg, success) {
        if (!holidaySyncAlert) return;
        holidaySyncAlert.textContent = msg;
        holidaySyncAlert.className = 'alert ' + (success ? 'success' : 'error');
        holidaySyncAlert.style.display = 'block';
        setTimeout(function () { holidaySyncAlert.style.display = 'none'; }, 7000);
    }

    function loadHolidayFiles() {
        if (!holidayFileList) return;
        fetch('/api/admin/holiday/files')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var files = data.files || [];
                holidayFileList.innerHTML = '';
                if (files.length === 0) {
                    holidayFileList.innerHTML =
                        '<li class="holiday-file-item"><span style="color:var(--muted);font-style:italic;">No holiday files found in holiday-upload/</span></li>';
                    return;
                }
                files.forEach(function (name) {
                    var li = document.createElement('li');
                    li.className = 'holiday-file-item';

                    var label = document.createElement('span');
                    label.className = 'hf-name';
                    label.textContent = name;

                    // ── Per-row indeterminate progress bar ────────────────────
                    var progressWrap = document.createElement('div');
                    progressWrap.className = 'hf-progress-wrap';
                    var progressBar = document.createElement('div');
                    progressBar.className = 'hf-progress-bar';
                    progressWrap.appendChild(progressBar);

                    // ── Sync button ───────────────────────────────────────────
                    var syncBtn = document.createElement('button');
                    syncBtn.type = 'button';
                    syncBtn.className = 'hf-sync-btn';
                    syncBtn.title = 'Sync Indian public holidays from this file';
                    syncBtn.innerHTML =
                        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" ' +
                        'stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">' +
                        '<polyline points="23 4 23 10 17 10"/>' +
                        '<path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>' +
                        '</svg>';

                    syncBtn.addEventListener('click', function (e) {
                        e.stopPropagation();
                        if (syncInProgress) return;
                        freezeFileList();
                        li.classList.add('syncing');
                        progressWrap.style.display = 'block';
                        syncBtn.querySelector('svg').style.display = 'none';
                        fetch('/api/admin/holiday/sync', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ filename: name })
                        })
                        .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, data: d }; }); })
                        .then(function (res) {
                            var msg;
                            if (res.ok) {
                                var written  = res.data.written  != null ? res.data.written  : 0;
                                var skipped  = res.data.skipped  != null ? res.data.skipped  : 0;
                                msg = (res.data.message || 'Sync complete.') +
                                      ' — ' + written + ' written, ' + skipped + ' skipped.';
                            } else {
                                msg = res.data.error || res.data.message || 'Sync failed.';
                            }
                            showHolidaySyncAlert(msg, res.ok);
                        })
                        .catch(function () {
                            showHolidaySyncAlert('Sync request failed. Please try again.', false);
                        })
                        .finally(function () {
                            progressWrap.style.display = 'none';
                            syncBtn.querySelector('svg').style.display = '';
                            li.classList.remove('syncing');
                            thawFileList();
                        });
                    });

                    // ── Delete button ─────────────────────────────────────────
                    var delBtn = document.createElement('button');
                    delBtn.type = 'button';
                    delBtn.className = 'hf-delete-btn';
                    delBtn.title = 'Delete this holiday file permanently';
                    delBtn.innerHTML =
                        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" ' +
                        'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
                        '<polyline points="3 6 5 6 21 6"/>' +
                        '<path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>' +
                        '<path d="M10 11v6"/><path d="M14 11v6"/>' +
                        '<path d="M9 6V4h6v2"/>' +
                        '</svg>';

                    delBtn.addEventListener('click', function (e) {
                        e.stopPropagation();
                        if (syncInProgress) return;
                        if (!confirm('Permanently delete "' + name + '" from holiday-upload/?')) return;
                        freezeFileList();
                        fetch('/api/admin/holiday/files', {
                            method: 'DELETE',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ filename: name })
                        })
                        .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, data: d }; }); })
                        .then(function (res) {
                            if (res.ok) {
                                showHolidaySyncAlert(res.data.message || 'File deleted.', true);
                                loadHolidayFiles(); // refresh the list
                            } else {
                                showHolidaySyncAlert(res.data.error || 'Delete failed.', false);
                                thawFileList();
                            }
                        })
                        .catch(function () {
                            showHolidaySyncAlert('Delete request failed. Please try again.', false);
                            thawFileList();
                        });
                    });
                    // ─────────────────────────────────────────────────────────

                    li.appendChild(label);
                    li.appendChild(progressWrap);
                    li.appendChild(syncBtn);
                    li.appendChild(delBtn);
                    holidayFileList.appendChild(li);
                });
            })
            .catch(function () {
                if (holidayFileList)
                    holidayFileList.innerHTML =
                        '<li class="holiday-file-item"><span style="color:#e57373;">Failed to load holiday files.</span></li>';
            });
    }

    loadHolidayFiles();

    // ── Card 5: Mapped Employee City ──────────────────────────────────────────

    var emcBody    = document.getElementById('emcTableBody');
    var emcAlert   = document.getElementById('emcAlert');
    var emcSaveBtn = document.getElementById('emcSaveBtn');

    // State loaded from APIs
    var emcEmployees = [];   // ordered list from active master (Col A, Col B = IN)
    var emcMapping   = {};   // { "Name": { city: [...], country: "IN" } }
    var emcCities    = [];   // primary city list from indian-city.json

    function loadEmcData() {
        if (!emcBody) return;
        Promise.all([
            fetch('/api/admin/holiday/indian-employees').then(function (r) { return r.json(); }),
            fetch('/api/admin/holiday/mapping').then(function (r) { return r.json(); }),
            fetch('/api/admin/holiday/cities').then(function (r) { return r.json(); })
        ]).then(function (results) {
            emcEmployees = results[0].employees || [];
            var warn     = results[0].warning;
            emcMapping   = (results[1].employees) || {};
            emcCities    = results[2].cities || {};
            if (warn) emcShowAlert(warn, false);
            renderEmcTable();
        }).catch(function () {
            emcShowAlert('Failed to load employee-city data.', false);
        });
    }

    function renderEmcTable() {
        if (!emcBody) return;
        emcBody.innerHTML = '';

        if (emcEmployees.length === 0) {
            emcBody.innerHTML =
                '<tr><td colspan="2" style="text-align:center;color:var(--muted);font-style:italic;padding:18px;">No Indian employees found in the active master file.</td></tr>';
            return;
        }

        var cityLabels = Object.keys(emcCities);

        emcEmployees.forEach(function (empName) {
            var tr = document.createElement('tr');

            // Name cell
            var tdName = document.createElement('td');
            tdName.innerHTML = '<span class="emc-employee-name">' + escapeHtml(empName) + '</span>';
            tr.appendChild(tdName);

            // City-options cell — one radio per entry in emcCities
            var tdCity = document.createElement('td');
            var optDiv = document.createElement('div');
            optDiv.className = 'emc-city-options';

            // Determine the currently stored comma-string for this employee
            var stored    = emcMapping[empName];
            var storedStr = (stored && stored.city) ? String(stored.city).trim() : '';

            cityLabels.forEach(function (cityLabel) {
                var cityValue = emcCities[cityLabel]; // e.g. "Bengaluru, Mysore"

                var lbl = document.createElement('label');
                lbl.className = 'emc-city-option';

                var radio = document.createElement('input');
                radio.type  = 'radio';
                radio.name  = 'emcCity_' + empName.replace(/\s+/g, '_');
                radio.value = cityValue;
                radio.dataset.employee = empName;
                if (storedStr === cityValue) radio.checked = true;

                var spanLabel = document.createElement('span');
                spanLabel.textContent = cityLabel;

                lbl.appendChild(radio);
                lbl.appendChild(spanLabel);
                optDiv.appendChild(lbl);
            });

            tdCity.appendChild(optDiv);
            tr.appendChild(tdCity);
            emcBody.appendChild(tr);
        });
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    if (emcSaveBtn) {
        emcSaveBtn.addEventListener('click', function () {
            var saves = [];
            emcEmployees.forEach(function (empName) {
                var radios = document.querySelectorAll(
                    'input[name="emcCity_' + empName.replace(/\s+/g, '_') + '"]:checked');
                if (radios.length === 0) return;
                saves.push({ employee: empName, cities: radios[0].value });
            });

            if (saves.length === 0) {
                emcShowAlert('No city selections to save.', false);
                return;
            }

            emcSaveBtn.disabled = true;
            var chain = Promise.resolve();
            saves.forEach(function (s) {
                chain = chain.then(function () {
                    return fetch('/api/admin/holiday/mapping', {
                        method: 'PUT',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(s)
                    }).then(function (r) { return r.json(); });
                });
            });
            chain
                .then(function () {
                    emcShowAlert('City mappings saved for ' + saves.length + ' employee(s).', true);
                    loadEmcData();
                })
                .catch(function () {
                    emcShowAlert('Failed to save one or more mappings.', false);
                })
                .finally(function () { emcSaveBtn.disabled = false; });
        });
    }

    function emcShowAlert(msg, success) {
        if (!emcAlert) return;
        emcAlert.textContent = msg;
        emcAlert.className = 'alert ' + (success ? 'success' : 'error');
        emcAlert.style.display = 'block';
        setTimeout(function () { emcAlert.style.display = 'none'; }, 5000);
    }

    loadEmcData();

})();
