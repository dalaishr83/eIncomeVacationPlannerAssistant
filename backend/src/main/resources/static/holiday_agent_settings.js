/**
 * holiday_agent_settings.js — Admin Settings page logic.
 * Extracted from admin/settings.html. Loaded exclusively on the Settings page.
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

    // ── Role Management widget ────────────────────────────────────────────────

    var rmEmployees = [];
    var rmAdmins    = [];

    var rmEmployeeList = document.getElementById('rmEmployeeList');
    var rmAdminList    = document.getElementById('rmAdminList');
    var rmPromoteBtn   = document.getElementById('rmPromoteBtn');
    var rmDemoteBtn    = document.getElementById('rmDemoteBtn');

    function rmLoadEmployees() {
        Promise.all([
            fetch('/api/admin/settings/employee-credentials').then(function (r) { return r.json(); }),
            fetch('/api/admin/settings/admin-credentials').then(function (r) { return r.json(); })
        ])
        .then(function (results) {
            rmEmployees = results[0].employees || [];
            rmAdmins    = results[1].admins    || [];
            rmRender();
        })
        .catch(function () {
            rmEmployeeList.innerHTML = '<li><span class="rm-list-empty">Failed to load employees</span></li>';
        });
    }

    function rmRender() {
        rmRenderList(rmEmployeeList, rmEmployees, 'employee');
        rmRenderList(rmAdminList,    rmAdmins,    'admin');
        rmSyncButtons();
    }

    function rmRenderList(ulEl, items, panel) {
        if (items.length === 0) {
            var emptyText = panel === 'admin' ? 'No admins promoted yet' : 'No employees loaded';
            ulEl.innerHTML = '<li><span class="rm-list-empty">' + emptyText + '</span></li>';
            return;
        }
        ulEl.innerHTML = '';
        items.forEach(function (emp) {
            var li = document.createElement('li');
            if (panel === 'admin') li.classList.add('admin-item');

            var dot = document.createElement('span');
            dot.className = 'rm-emp-dot';
            li.appendChild(dot);

            var nameSpan = document.createElement('span');
            nameSpan.textContent = emp.employee_name
                ? emp.employee_name + ' (' + emp.username + ')'
                : emp.username;
            li.appendChild(nameSpan);

            li.addEventListener('click', function () {
                li.classList.toggle('selected');
                rmSyncButtons();
            });

            ulEl.appendChild(li);
        });
    }

    function rmSyncButtons() {
        var empSelected   = rmEmployeeList.querySelectorAll('li.selected').length;
        var adminSelected = rmAdminList.querySelectorAll('li.selected').length;
        rmPromoteBtn.disabled = empSelected === 0;
        rmDemoteBtn.disabled  = adminSelected === 0;
    }

    function rmGetSelected(ulEl, stateArr) {
        var result = [];
        ulEl.querySelectorAll('li.selected').forEach(function (li) {
            var idx = Array.prototype.indexOf.call(ulEl.querySelectorAll('li'), li);
            if (idx >= 0 && idx < stateArr.length) result.push(stateArr[idx]);
        });
        return result;
    }

    rmPromoteBtn.addEventListener('click', function () {
        var toPromote = rmGetSelected(rmEmployeeList, rmEmployees);
        if (toPromote.length === 0) return;
        rmPromoteBtn.disabled = true;
        rmDemoteBtn.disabled  = true;
        fetch('/api/admin/settings/promote', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ usernames: toPromote.map(function (e) { return e.username; }) })
        })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data.error) {
                rmShowAlert('rmAlert', data.error, false);
                rmSyncButtons();
            } else {
                var msg = data.message + (data.errors && data.errors.length ? ' Errors: ' + data.errors.join('; ') : '');
                rmLoadEmployees();
                rmShowAlert('rmAlert', msg, true);
            }
        })
        .catch(function () {
            rmShowAlert('rmAlert', 'Request failed. Role change not saved.', false);
            rmSyncButtons();
        });
    });

    rmDemoteBtn.addEventListener('click', function () {
        var toDemote = rmGetSelected(rmAdminList, rmAdmins);
        if (toDemote.length === 0) return;
        rmPromoteBtn.disabled = true;
        rmDemoteBtn.disabled  = true;
        fetch('/api/admin/settings/demote', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ usernames: toDemote.map(function (e) { return e.username; }) })
        })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data.error) {
                rmShowAlert('rmAlert', data.error, false);
                rmSyncButtons();
            } else {
                var msg = data.message + (data.errors && data.errors.length ? ' Errors: ' + data.errors.join('; ') : '');
                rmLoadEmployees();
                rmShowAlert('rmAlert', msg, true);
            }
        })
        .catch(function () {
            rmShowAlert('rmAlert', 'Request failed. Role change not saved.', false);
            rmSyncButtons();
        });
    });

    function rmShowAlert(id, msg, success) {
        var el = document.getElementById(id);
        el.textContent = msg;
        el.className = 'alert ' + (success ? 'success' : 'error');
        el.style.display = 'block';
        setTimeout(function () { el.style.display = 'none'; }, 5000);
    }

    rmLoadEmployees();

    // ── Load employee credentials into the dropdown ───────────────────────────
    function loadEmployeeDropdown() {
        fetch('/api/admin/settings/employee-credentials')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var sel = document.getElementById('employeeSelect');
                var employees = data.employees || [];
                sel.innerHTML = '';
                if (employees.length === 0) {
                    sel.innerHTML = '<option value="">— No employees loaded —</option>';
                    return;
                }
                sel.innerHTML = '<option value="">— Select an employee —</option>';
                employees.forEach(function (emp) {
                    var opt = document.createElement('option');
                    opt.value = emp.username;
                    opt.textContent = emp.employee_name
                        ? emp.employee_name + ' (' + emp.username + ')'
                        : emp.username;
                    sel.appendChild(opt);
                });
            })
            .catch(function () {
                document.getElementById('employeeSelect').innerHTML =
                    '<option value="">— Failed to load employees —</option>';
            });
    }
    loadEmployeeDropdown();

    // ── Load admin credentials into the dropdown ──────────────────────────────
    function loadAdminDropdown() {
        fetch('/api/admin/settings/admin-credentials')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var sel = document.getElementById('adminSelect');
                var admins = data.admins || [];
                sel.innerHTML = '';
                if (admins.length === 0) {
                    sel.innerHTML = '<option value="">— No admins found —</option>';
                    return;
                }
                sel.innerHTML = '<option value="">— Select an admin —</option>';
                admins.forEach(function (adm) {
                    var opt = document.createElement('option');
                    opt.value = adm.username;
                    opt.textContent = adm.employee_name
                        ? adm.employee_name + ' (' + adm.username + ')'
                        : adm.username;
                    sel.appendChild(opt);
                });
            })
            .catch(function () {
                document.getElementById('adminSelect').innerHTML =
                    '<option value="">— Failed to load admins —</option>';
            });
    }
    loadAdminDropdown();

    // ── Save restricted types ─────────────────────────────────────────────────
    document.getElementById('saveRestrictedBtn').addEventListener('click', function () {
        var checked = [];
        document.querySelectorAll('.type-toggle:checked').forEach(function (cb) { checked.push(cb.value); });
        fetch('/api/admin/settings/restricted-types', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ restricted_types: checked })
        }).then(function (r) { return r.json(); }).then(function (data) {
            showAlert('restrictedAlert', data.error ? data.error : data.message, !data.error);
        }).catch(function () { showAlert('restrictedAlert', 'Request failed.', false); });
    });

    // ── Reset admin password ──────────────────────────────────────────────────
    document.getElementById('resetAdminPasswordBtn').addEventListener('click', function () {
        var username = document.getElementById('adminSelect').value;
        var pwd      = document.getElementById('adminNewPassword').value;
        if (!username) { showAlert('adminPasswordAlert', 'Please select an admin user.', false); return; }
        if (!pwd || pwd.length < 6) { showAlert('adminPasswordAlert', 'Password must be at least 6 characters.', false); return; }
        fetch('/api/admin/settings/password-reset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ role: username, new_password: pwd })
        }).then(function (r) { return r.json(); }).then(function (data) {
            showAlert('adminPasswordAlert', data.error ? data.error : data.message, !data.error);
            if (!data.error) document.getElementById('adminNewPassword').value = '';
        }).catch(function () { showAlert('adminPasswordAlert', 'Request failed.', false); });
    });

    // ── Reset employee password ───────────────────────────────────────────────
    document.getElementById('resetEmployeePasswordBtn').addEventListener('click', function () {
        var username = document.getElementById('employeeSelect').value;
        var pwd      = document.getElementById('employeeNewPassword').value;
        if (!username) { showAlert('employeePasswordAlert', 'Please select an employee.', false); return; }
        if (!pwd || pwd.length < 6) { showAlert('employeePasswordAlert', 'Password must be at least 6 characters.', false); return; }
        fetch('/api/admin/settings/password-reset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ role: username, new_password: pwd })
        }).then(function (r) { return r.json(); }).then(function (data) {
            showAlert('employeePasswordAlert', data.error ? data.error : data.message, !data.error);
            if (!data.error) document.getElementById('employeeNewPassword').value = '';
        }).catch(function () { showAlert('employeePasswordAlert', 'Request failed.', false); });
    });

    // ── Shared alert helper ───────────────────────────────────────────────────
    function showAlert(id, msg, success) {
        var el = document.getElementById(id);
        el.textContent = msg;
        el.className = 'alert ' + (success ? 'success' : 'error');
        el.style.display = 'block';
        setTimeout(function () { el.style.display = 'none'; }, 5000);
    }

    // ── Card 4: Sync Indian Holiday ───────────────────────────────────────────

    var holidayFileList = document.getElementById('holidayFileList');

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
                files.forEach(function (name, idx) {
                    var li = document.createElement('li');
                    li.className = 'holiday-file-item';

                    var radio = document.createElement('input');
                    radio.type = 'radio';
                    radio.name = 'holidayFile';
                    radio.value = name;
                    radio.id = 'hf_' + idx;

                    var label = document.createElement('label');
                    label.htmlFor = 'hf_' + idx;
                    label.className = 'hf-name';
                    label.textContent = name;
                    label.style.cursor = 'pointer';

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
                        e.stopPropagation(); // don't trigger the li click / radio select
                        syncBtn.disabled = true;
                        syncBtn.classList.add('spinning');
                        fetch('/api/admin/holiday/sync', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ filename: name })
                        })
                        .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, data: d }; }); })
                        .then(function (res) {
                            showHolidaySyncAlert(
                                res.data.message || (res.ok ? 'Sync complete.' : 'Sync failed.'),
                                res.ok
                            );
                        })
                        .catch(function () {
                            showHolidaySyncAlert('Sync request failed. Please try again.', false);
                        })
                        .finally(function () {
                            syncBtn.disabled = false;
                            syncBtn.classList.remove('spinning');
                        });
                    });
                    // ─────────────────────────────────────────────────────────

                    li.addEventListener('click', function () {
                        radio.checked = true;
                        holidayFileList.querySelectorAll('.holiday-file-item').forEach(function (el) {
                            el.classList.remove('selected');
                        });
                        li.classList.add('selected');
                    });

                    li.appendChild(radio);
                    li.appendChild(label);
                    li.appendChild(syncBtn);
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

    var emcBody   = document.getElementById('emcTableBody');
    var emcAlert  = document.getElementById('emcAlert');
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
            // stored.city is a plain string e.g. "Bengaluru, Mysore"
            var stored = emcMapping[empName];
            var storedStr = (stored && stored.city) ? String(stored.city).trim() : '';

            cityLabels.forEach(function (cityLabel) {
                var cityValue = emcCities[cityLabel]; // e.g. "Bengaluru, Mysore"

                var lbl = document.createElement('label');
                lbl.className = 'emc-city-option';

                var radio = document.createElement('input');
                radio.type  = 'radio';
                radio.name  = 'emcCity_' + empName.replace(/\s+/g, '_');
                radio.value = cityValue;          // plain comma-separated string
                radio.dataset.employee = empName;
                if (storedStr === cityValue) radio.checked = true;

                var spanLabel = document.createElement('span');
                spanLabel.textContent = cityLabel; // display label from JSON key

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
            // Collect all selected radio values per employee
            var saves = [];
            emcEmployees.forEach(function (empName) {
                var radios = document.querySelectorAll(
                    'input[name="emcCity_' + empName.replace(/\s+/g, '_') + '"]:checked');
                if (radios.length === 0) return;
                var radio = radios[0];
                // radio.value is a plain comma-separated string, e.g. "Bengaluru, Mysore"
                saves.push({ employee: empName, cities: radio.value });
            });

            if (saves.length === 0) {
                emcShowAlert('No city selections to save.', false);
                return;
            }

            emcSaveBtn.disabled = true;
            // Save each mapping sequentially
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
                    loadEmcData(); // refresh to confirm persisted state
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
