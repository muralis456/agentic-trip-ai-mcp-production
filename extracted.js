
    const layout = document.querySelector('.layout');
    const form = document.getElementById('travelForm');
    const chatThread = document.getElementById('chatThread');
    const messagesPane = document.getElementById('messagesPane');
    const chatHistoryList = document.getElementById('chatHistoryList');
    const userIdField = document.getElementById('userId');
    const promptInput = document.getElementById('tripPrompt');
    const submitButton = document.getElementById('submitButton');
    const composerBar = document.getElementById('composerBar');
    const modifyComposerBar = document.getElementById('modifyComposerBar');
    const modifyComposerSub = document.getElementById('modifyComposerSub');
    const cancelModify = document.getElementById('cancelModify');
    const loadingState = document.getElementById('loadingState');
    const newChatBtn = document.getElementById('newChatBtn');
    const composerNewChat = document.getElementById('composerNewChat');
    const tripsView = document.getElementById('tripsView');
    const tripsGrid = document.getElementById('tripsGrid');
    const tripsRefresh = document.getElementById('tripsRefresh');
    const settingsView = document.getElementById('settingsView');
    const settingsFirstName = document.getElementById('settingsFirstName');
    const settingsLastName = document.getElementById('settingsLastName');
    const settingsEmail = document.getElementById('settingsEmail');
    const settingsUsername = document.getElementById('settingsUsername');
    const settingsRole = document.getElementById('settingsRole');
    const changePasswordForm = document.getElementById('changePasswordForm');
    const currentPasswordInput = document.getElementById('currentPassword');
    const newPasswordInput = document.getElementById('newPassword');
    const confirmNewPasswordInput = document.getElementById('confirmNewPassword');
    const changePasswordButton = document.getElementById('changePasswordButton');
    const passwordMessage = document.getElementById('passwordMessage');
    const historyDeleteModal = document.getElementById('historyDeleteModal');
    const historyDeleteItem = document.getElementById('historyDeleteItem');
    const historyDeleteNote = document.getElementById('historyDeleteNote');
    const historyDeleteCancel = document.getElementById('historyDeleteCancel');
    const historyDeleteConfirm = document.getElementById('historyDeleteConfirm');
    const signedInUser = document.getElementById('signedInUser');
    let currentUserId = '';

    const csrfMeta = document.querySelector('meta[name="_csrf"]');
    const csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');
    const apiFetch = (url, options = {}) => {
        const opts = { ...options, headers: { ...(options.headers || {}) } };
        const method = String(opts.method || 'GET').toUpperCase();
        if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrfMeta?.content && csrfHeaderMeta?.content) {
            opts.headers[csrfHeaderMeta.content] = csrfMeta.content;
        }
        return window.fetch(url, opts).then(response => {
            if (response.status === 401 && !String(url).startsWith('/api/auth/')) {
                window.location.href = '/login?expired=true';
            }
            return response;
        });
    };

    let dbTrips = [];
    let pendingHistoryDelete = null;
    let modifyContext = null;
    // Unified selection key for Recent Trips/Chats. DB rows use db:<id>; local
    // conversations use chat:<id>. This is intentionally separate from
    // currentChatId because the visible history can contain both kinds.
    let currentHistoryKey = null;

    const showToast = (message) => {
        let toast = document.getElementById('uiToast');
        if (!toast) {
            toast = document.createElement('div');
            toast.id = 'uiToast';
            toast.className = 'ui-toast';
            document.body.appendChild(toast);
        }
        toast.textContent = message;
        toast.classList.add('visible');
        clearTimeout(window.__agenticTripToast);
        window.__agenticTripToast = setTimeout(() => toast.classList.remove('visible'), 2600);
    };

    const setActiveNav = (name) => {
        document.querySelectorAll('.sidebar-nav-item').forEach(item =>
            item.classList.toggle('active', item.dataset.nav === name));
    };

    const showHomeView = () => {
        tripsView.classList.remove('visible');
        settingsView.classList.remove('visible');
        chatThread.style.display = '';
        const welcome = document.querySelector('.welcome-block');
        if (welcome) welcome.style.display = '';
        setActiveNav('home');
    };

    const showTripsView = async () => {
        tripsView.classList.add('visible');
        chatThread.style.display = 'none';
        const welcome = document.querySelector('.welcome-block');
        if (welcome) welcome.style.display = 'none';
        setActiveNav('trips');
        await loadDbTrips(true);
        messagesPane.scrollTo({ top: 0, behavior: 'smooth' });
    };

    const loadSettings = async () => {
        try {
            const response = await apiFetch('/api/auth/me', { cache: 'no-store' });
            if (!response.ok) throw new Error('Could not load account details.');
            const identity = await response.json();
            const setValue = (element, value) => {
                if (!element) return;
                const text = String(value || '').trim();
                element.textContent = text || 'Not provided';
                element.classList.toggle('muted', !text);
            };
            setValue(settingsFirstName, identity.firstName);
            setValue(settingsLastName, identity.lastName);
            setValue(settingsEmail, identity.email);
            setValue(settingsUsername, identity.username);
            setValue(settingsRole, identity.role);
            if (signedInUser) signedInUser.textContent = [identity.firstName, identity.lastName].filter(Boolean).join(' ') || identity.username || identity.userId || '';
        } catch (error) {
            showToast(error.message || 'Unable to load account details.');
        }
    };

    const showSettingsView = async () => {
        tripsView.classList.remove('visible');
        settingsView.classList.add('visible');
        chatThread.style.display = 'none';
        const welcome = document.querySelector('.welcome-block');
        if (welcome) welcome.style.display = 'none';
        setActiveNav('settings');
        await loadSettings();
        messagesPane.scrollTo({ top: 0, behavior: 'smooth' });
    };

    document.querySelectorAll('.sidebar-nav-item').forEach(item => {
        item.addEventListener('click', async () => {
            const nav = item.dataset.nav;
            if (nav === 'trips') {
                await showTripsView();
            } else if (nav === 'home') {
                showHomeView();
                messagesPane.scrollTo({ top: 0, behavior: 'smooth' });
            } else if (nav === 'tools') {
                showHomeView();
                promptInput.focus();
                showToast('Travel Tools: describe flights, hotels, weather, itinerary, budget, or a complete trip.');
            } else if (nav === 'saved') {
                showHomeView();
                showToast('Saved Places will be connected to your saved destinations next.');
            } else if (nav === 'settings') {
                await showSettingsView();
            } else if (nav === 'help') {
                showHomeView();
                showToast('Use New Trip to start fresh. Review the plan, then approve, modify, or reject it.');
            }
        });
    });

    const resizePrompt = () => {
        // Let the browser recalculate the textarea's natural height first.
        // Using height=0 can be unreliable when the textarea also has a
        // min-height/flex parent, which is why long prompts were not growing.
        const minHeight = 28;
        const maxHeight = 220;

        promptInput.style.height = 'auto';
        promptInput.style.overflowY = 'hidden';

        const contentHeight = Math.max(promptInput.scrollHeight, minHeight);
        const nextHeight = Math.min(contentHeight, maxHeight);
        promptInput.style.height = nextHeight + 'px';

        const overflowing = contentHeight > maxHeight + 1;
        promptInput.classList.toggle('has-overflow', overflowing);
        promptInput.style.overflowY = overflowing ? 'auto' : 'hidden';
        if (overflowing) {
            promptInput.scrollTop = promptInput.scrollHeight;
        }
    };

    const updateComposerState = () => {
        // The submit handler already ignores blank requests. Keep the send/Enter
        // control enabled while the composer is idle, including after New Chat.
        if (!loadingState.classList.contains('visible')) {
            submitButton.disabled = false;
        }
    };

    const exitModifyMode = (clearInput = false) => {
        modifyContext = null;
        modifyComposerBar?.classList.remove('visible');
        composerBar?.classList.remove('modify-active');
        if (modifyComposerSub) {
            modifyComposerSub.textContent = 'Describe the change. Only the affected specialist(s) will run.';
        }
        promptInput.placeholder = 'Ask anything — plan a trip, get travel advice, check weather, find hotels, or ask about a destination…';
        if (clearInput) {
            promptInput.value = '';
            
    resizePrompt();
        }
    };

    const enterModifyMode = (threadId, sourceRow) => {
        if (!threadId) {
            showToast('This plan is no longer available for modification.');
            return;
        }
        modifyContext = { threadId: String(threadId), sourceRow: sourceRow || null };
        modifyComposerBar?.classList.add('visible');
        composerBar?.classList.add('modify-active');
        if (modifyComposerSub) {
            modifyComposerSub.textContent = 'Current plan retained · tell me exactly what to change; only affected agents will run.';
        }
        promptInput.placeholder = 'What should we change in this plan?';
        promptInput.focus();
        
    resizePrompt();
        showToast('Modify mode enabled — your next message will update this trip.');
    };

    if (cancelModify) {
        cancelModify.addEventListener('click', () => {
            exitModifyMode(false);
            promptInput.focus();
        });
    }

    const storageKey = () => 'agentic-trip-ai-chats:' + (currentUserId || userIdField.value.trim() || 'authenticated-user');
    let currentChatId = null;
    let startedFreshChat = false;

    const syncUserIdField = () => {
        form.querySelector('input[name="userId"]').value = currentUserId || userIdField.value.trim() || '';
    };

    const syncUserId = () => {
        syncUserIdField();
        renderHistoryList();
        loadServerHistoryIntoStore();
    };

    // Identity is supplied by the authenticated Spring Security session; users cannot edit it.

    document.querySelectorAll('.city-card[data-prompt]').forEach(card => {
        card.addEventListener('click', () => {
            promptInput.value = card.getAttribute('data-prompt');
            
    resizePrompt();
            promptInput.focus();
        });
    });

    const loadStore = () => {
        try {
            return JSON.parse(localStorage.getItem(storageKey()) || '{"chats":[]}');
        } catch (e) {
            return { chats: [] };
        }
    };

    const trimPlanData = (planData) => {
        if (!planData) {
            return null;
        }
        // Keep the complete UI response for browser fallback. The server is
        // authoritative when a threadId exists, but retaining specialist fields
        // here prevents a blank/generic card if the API is temporarily unavailable.
        return JSON.parse(JSON.stringify(planData));
    };

    const saveStore = (store) => {
        const chats = (store.chats || [])
            .slice()
            .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
        const kept = [];
        for (const chat of chats) {
            if (kept.length >= 20 && chat.id !== currentChatId) {
                continue;
            }
            kept.push({
                id: chat.id,
                threadId: chat.threadId || '',
                title: chat.title,
                updatedAt: chat.updatedAt,
                messages: (chat.messages || []).slice(-40).map(m => ({
                    role: m.role,
                    text: (m.text || '').slice(0, 8000),
                    planData: trimPlanData(m.planData)
                }))
            });
        }
        store.chats = kept;
        try {
            localStorage.setItem(storageKey(), JSON.stringify(store));
        } catch (e) {
            console.warn('Could not save chat history locally', e);
            try {
                store.chats = kept.map(chat => ({
                    ...chat,
                    messages: (chat.messages || []).map(m => ({ role: m.role, text: m.text, planData: null }))
                }));
                localStorage.setItem(storageKey(), JSON.stringify(store));
            } catch (ignored) {
                // ignore
            }
        }
    };

    const ensureCurrentChat = (titleHint) => {
        const store = loadStore();
        let chat = store.chats.find(c => c.id === currentChatId);
        if (!chat) {
            currentChatId = 'chat-' + Date.now();
            chat = {
                id: currentChatId,
                title: (titleHint || 'New trip').slice(0, 60),
                updatedAt: Date.now(),
                messages: []
            };
            store.chats.unshift(chat);
            saveStore(store);
        }
        return chat;
    };

    const persistMessage = (role, text, planData) => {
        try {
            const store = loadStore();
            let chat = store.chats.find(c => c.id === currentChatId);
            if (!chat) {
                ensureCurrentChat(text);
                return persistMessage(role, text, planData);
            }
            chat.messages.push({ role, text, planData: planData || null });
            if (planData?.threadId) {
                chat.threadId = String(planData.threadId);
            }
            chat.updatedAt = Date.now();
            if (role === 'user' && chat.messages.filter(m => m.role === 'user').length === 1) {
                chat.title = text.slice(0, 60);
            }
            saveStore(store);
            renderHistoryList();
        } catch (e) {
            console.warn('persistMessage failed', e);
        }
    };

    const loadServerHistoryIntoStore = async () => {
        const userId = currentUserId || userIdField.value.trim() || '';
        try {
            const res = await apiFetch('/api/chat/history?userId=' + encodeURIComponent(userId) + '&limit=100&_=' + Date.now(), { cache: 'no-store' });
            if (!res.ok) return;
            const items = await res.json();
            if (!Array.isArray(items) || !items.length) return;

            const store = loadStore();
            const serverPrefix = 'server-' + userId + '-';
            const existingLocal = store.chats.filter(c => !String(c.id || '').startsWith(serverPrefix));

            const grouped = new Map();
            items.forEach(item => {
                const sessionId = String(item.sessionId || '').trim();
                if (!sessionId) return;
                if (!grouped.has(sessionId)) {
                    grouped.set(sessionId, {
                        id: serverPrefix + sessionId,
                        threadId: sessionId,
                        title: 'Previous conversation',
                        updatedAt: 0,
                        messages: []
                    });
                }
                const chat = grouped.get(sessionId);
                const createdAt = item.createdAt ? new Date(item.createdAt).getTime() : Date.now();
                chat.updatedAt = Math.max(chat.updatedAt, Number.isFinite(createdAt) ? createdAt : Date.now());
                if (item.role === 'user') {
                    const text = String(item.content || '').trim();
                    if (!chat.messages.some(m => m.role === 'user') && text) {
                        chat.title = text.slice(0, 60) || 'Previous conversation';
                    }
                    chat.messages.push({ role: 'user', text, planData: null });
                } else {
                    let planData = null;
                    if (item.structuredData) {
                        try { planData = JSON.parse(item.structuredData); } catch (ignored) {}
                    }
                    chat.messages.push({
                        role: 'assistant',
                        text: item.content || '',
                        planData
                    });
                }
            });

            // Merge server data into an existing local chat when both refer to
            // the same thread. Server structuredData is the durable source of
            // truth and fixes older local entries that only contain plain text.
            const serverChats = Array.from(grouped.values());
            const mergedLocal = existingLocal.map(chat => {
                const threadId = chatThreadIdForDelete(chat);
                if (!threadId) return chat;
                const server = grouped.get(threadId);
                if (!server) return chat;
                const serverHasStructured = server.messages.some(m => m.role === 'assistant' && m.planData);
                return {
                    ...chat,
                    threadId,
                    title: server.title || chat.title,
                    updatedAt: Math.max(chat.updatedAt || 0, server.updatedAt || 0),
                    messages: serverHasStructured ? server.messages : (chat.messages || [])
                };
            });

            const localThreadIds = new Set(mergedLocal.map(chat => chatThreadIdForDelete(chat)).filter(Boolean));
            const authoritativeThreads = new Set(dbTrips.map(t => String(t.threadId || '')).filter(Boolean));
            mergedLocal.forEach(chat => {
                const threadId = chatThreadIdForDelete(chat);
                if (threadId) authoritativeThreads.add(threadId);
            });

            const unmergedServerChats = serverChats.filter(chat =>
                !localThreadIds.has(String(chat.threadId || ''))
                && !authoritativeThreads.has(String(chat.threadId || ''))
            );

            store.chats = mergedLocal.concat(unmergedServerChats);
            saveStore(store);
            renderHistoryList();
        } catch (e) {
            console.warn('Could not load server chat history', e);
        }
    };

    const tripStatusLabel = (trip) => {
        if (trip?.legacy) return 'Previous trip';
        const status = String(trip?.status || '').toUpperCase();
        if (trip?.awaitingApproval || status === 'PENDING_APPROVAL') return 'Pending approval';
        if (status === 'REJECTED') return 'Rejected';
        if (status === 'COMPLETE') return 'Confirmed';
        return status ? status.replaceAll('_', ' ') : 'Saved';
    };

    const renderDbTripsView = () => {
        if (!dbTrips.length) {
            tripsGrid.innerHTML = '<div class="trips-empty" style="grid-column:1/-1"><strong>No trips saved yet</strong>Create a new trip and your completed/pending plan will appear here automatically.</div>';
            return;
        }
        tripsGrid.innerHTML = dbTrips.map(t => {
            const route = [t.origin, t.destination].filter(Boolean).join(' → ') || t.title || 'Trip';
            const dates = formatDateRange(t.departureDate, t.returnDate);
            const status = tripStatusLabel(t);
            const statusClass = status === 'Rejected' ? ' rejected' : status === 'Pending approval' ? ' pending' : '';
            const meta = [dates, t.travelers ? `${t.travelers} traveler${Number(t.travelers) === 1 ? '' : 's'}` : '', t.budgetLabel || '', t.qualityScore ? `Quality ${t.qualityScore}/100` : ''].filter(Boolean);
            const openLabel = t.legacy ? 'Open previous conversation →' : 'Open saved plan →';
            return '<article class="trip-history-card">'
                + '<div class="trip-history-top"><div><div class="trip-history-route">' + escapeHtml(route) + '</div><div class="trip-history-date">' + (t.legacy ? 'Recovered from conversation history · ' : 'Updated ') + escapeHtml(t.updatedAt ? new Date(t.updatedAt).toLocaleString() : '') + '</div></div>'
                + '<span class="trip-history-status' + statusClass + '">' + escapeHtml(status) + '</span></div>'
                + '<div class="trip-history-meta">' + meta.map(m => '<span class="trip-history-chip">' + escapeHtml(m) + '</span>').join('') + '</div>'
                + '<button type="button" class="trip-history-open" data-trip-id="' + escapeHtml(String(t.id)) + '">' + openLabel + '</button>'
                + '</article>';
        }).join('');
    };

    const loadDbTrips = async (renderView = false) => {
        const userId = currentUserId || userIdField.value.trim() || '';
        const refreshButton = document.getElementById('tripsRefresh');
        const previousLabel = refreshButton ? refreshButton.textContent : '';
        if (refreshButton && renderView) {
            refreshButton.disabled = true;
            refreshButton.textContent = '↻ Refreshing…';
            refreshButton.setAttribute('aria-busy', 'true');
        }
        try {
            // Never let browser/proxy caching make My Trips appear stale.
            const url = '/api/trips?userId=' + encodeURIComponent(userId)
                + '&limit=50&_=' + Date.now();
            const res = await apiFetch(url, { cache: 'no-store' });
            if (!res.ok) throw new Error('Unable to load trips');
            const items = await res.json();
            dbTrips = Array.isArray(items) ? items : [];
            if (renderView) renderDbTripsView();
            renderHistoryList();
            if (renderView) {
                const stamp = document.getElementById('tripsRefreshStamp');
                if (stamp) stamp.textContent = 'Updated just now';
                showToast('My Trips refreshed.');
            }
            return true;
        } catch (e) {
            if (renderView) tripsGrid.innerHTML = '<div class="trips-empty" style="grid-column:1/-1"><strong>Could not load My Trips</strong>Check the server connection and try Refresh again.</div>';
            console.warn('Could not load database trips', e);
            if (renderView) showToast('Could not refresh My Trips.');
            return false;
        } finally {
            if (refreshButton && renderView) {
                refreshButton.disabled = false;
                refreshButton.textContent = previousLabel || '↻ Refresh';
                refreshButton.removeAttribute('aria-busy');
            }
        }
    };

    const openSavedTrip = async (id) => {
        try {
            currentHistoryKey = 'db:' + String(id);
            renderHistoryList();
            const trip = dbTrips.find(t => String(t.id) === String(id));
            const userId = currentUserId || userIdField.value.trim() || '';
            showHomeView();
            clearThreadDom();

            if (trip?.legacy) {
                const sourceMessageId = Math.abs(Number(id));
                const messageRes = await apiFetch('/api/chat/message/' + encodeURIComponent(sourceMessageId)
                    + '?userId=' + encodeURIComponent(userId));
                const sourceMessage = await messageRes.json().catch(() => ({}));
                if (!messageRes.ok || !sourceMessage.sessionId) throw new Error(safeUiErrorMessage());

                const res = await apiFetch('/api/chat/session/' + encodeURIComponent(sourceMessage.sessionId)
                    + '?userId=' + encodeURIComponent(userId) + '&limit=100');
                const messages = await res.json().catch(() => []);
                if (!res.ok || !Array.isArray(messages)) throw new Error(safeUiErrorMessage());
                let lastUser = '';
                messages.forEach(msg => {
                    if (msg.role === 'user') {
                        lastUser = msg.content || '';
                        renderUserMessage(lastUser, false);
                    } else {
                        renderAssistantMessage({ text: msg.content || '' }, false, lastUser);
                    }
                });
                scrollChat();
                return;
            }

            const res = await apiFetch('/api/trips/' + encodeURIComponent(id) + '?userId=' + encodeURIComponent(userId));
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.error || safeUiErrorMessage());
            appendAssistantMessage(data, false);
            scrollChat();
        } catch (e) {
            showToast(e?.message || safeUiErrorMessage());
        }
    };

    tripsGrid.addEventListener('click', event => {
        const btn = event.target.closest('[data-trip-id]');
        if (btn) openSavedTrip(btn.getAttribute('data-trip-id'));
    });
    tripsRefresh.addEventListener('click', async (event) => {
        event.preventDefault();
        // Refresh both the saved-trip view and the conversation-backed Recent
        // History. This keeps My Trips and the sidebar in sync after a new chat,
        // approval, deletion, or a second browser tab creates a new record.
        const ok = await loadDbTrips(true);
        if (ok) {
            await loadServerHistoryIntoStore();
            renderDbTripsView();
            renderHistoryList();
        }
    });

    const renderHistoryList = () => {
        chatHistoryList.innerHTML = '';
        const store = loadStore();

        const chatThreadId = (chat) => {
            if (chat?.threadId) return String(chat.threadId);
            for (const message of (chat?.messages || [])) {
                const id = message?.planData?.threadId;
                if (id) return String(id);
            }
            return '';
        };

        const dbThreadIds = new Set(dbTrips.map(t => String(t.threadId || '')).filter(Boolean));
        const localChats = store.chats
            .filter(chat => !dbThreadIds.has(chatThreadId(chat)))
            .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
            .slice(0, 12);

        // One unified newest-first history. Every row also carries its real
        // session/thread so Delete can remove the corresponding DB memory.
        const entries = [];
        dbTrips.forEach(trip => entries.push({
            kind: 'db',
            id: trip.id,
            threadId: String(trip.threadId || ''),
            legacy: Boolean(trip.legacy),
            title: [trip.origin, trip.destination].filter(Boolean).join(' → ') || trip.title || 'Saved trip',
            meta: tripStatusLabel(trip) + ' · ' + (trip.updatedAt ? new Date(trip.updatedAt).toLocaleDateString() : ''),
            updatedAt: trip.updatedAt ? new Date(trip.updatedAt).getTime() : 0
        }));
        localChats.forEach(chat => entries.push({
            kind: 'chat',
            id: chat.id,
            threadId: chatThreadId(chat),
            title: chat.title || 'Recent conversation',
            meta: 'Recent · ' + (chat.updatedAt ? new Date(chat.updatedAt).toLocaleDateString() : ''),
            updatedAt: chat.updatedAt || 0
        }));

        entries.sort((a, b) => b.updatedAt - a.updatedAt);

        if (!entries.length) {
            const empty = document.createElement('div');
            empty.className = 'chat-history-empty';
            empty.textContent = 'No trips or conversations yet.';
            chatHistoryList.appendChild(empty);
            return;
        }

        entries.slice(0, 12).forEach(entry => {
            const wrap = document.createElement('div');
            wrap.className = 'chat-history-item-wrap';

            const btn = document.createElement('button');
            btn.type = 'button';
            const entryKey = entry.kind + ':' + String(entry.id);
            const isSelected = entryKey === currentHistoryKey;
            btn.className = 'chat-history-item' + (isSelected ? ' active' : '');
            btn.dataset.historyKey = entryKey;
            if (isSelected) {
                btn.setAttribute('aria-current', 'page');
                wrap.classList.add('selected');
            } else {
                btn.removeAttribute('aria-current');
            }
            const title = document.createElement('span');
            title.textContent = entry.title;
            if (entry.kind === 'db') {
                const status = document.createElement('span');
                status.className = 'db-status';
                status.textContent = 'DB';
                title.appendChild(status);
            }
            const meta = document.createElement('span');
            meta.className = 'meta';
            meta.textContent = entry.meta;
            btn.appendChild(title);
            btn.appendChild(meta);
            btn.onclick = () => entry.kind === 'db' ? openSavedTrip(entry.id) : openChat(entry.id);

            const deleteButton = document.createElement('button');
            deleteButton.type = 'button';
            deleteButton.className = 'chat-history-delete-btn';
            deleteButton.setAttribute('aria-label', 'Delete history');
            deleteButton.setAttribute('title', 'Delete');
            deleteButton.innerHTML = '<span aria-hidden="true">⌫</span>';
            deleteButton.addEventListener('click', async event => {
                event.preventDefault();
                event.stopPropagation();
                await deleteHistoryEntry(entry, wrap);
            });

            wrap.appendChild(btn);
            wrap.appendChild(deleteButton);
            chatHistoryList.appendChild(wrap);
        });
    };

    const closeDeleteHistoryModal = (result = false) => {
        if (!historyDeleteModal) return;
        historyDeleteModal.classList.remove('visible');
        historyDeleteModal.setAttribute('aria-hidden', 'true');
        document.body.classList.remove('history-delete-open');
        const pending = pendingHistoryDelete;
        pendingHistoryDelete = null;
        if (pending) pending.resolve(result);
    };

    const askDeleteHistoryConfirmation = (entry) => new Promise(resolve => {
        pendingHistoryDelete = { resolve };
        historyDeleteItem.textContent = entry.title || 'this history item';
        historyDeleteNote.textContent = entry.kind === 'db' && !entry.legacy
            ? 'This also permanently deletes the saved trip record and its conversation memory.'
            : entry.threadId
                ? 'This permanently deletes the saved conversation memory for this session.'
                : 'This removes the item from your local Recent History.';
        historyDeleteModal.classList.add('visible');
        historyDeleteModal.setAttribute('aria-hidden', 'false');
        document.body.classList.add('history-delete-open');
        requestAnimationFrame(() => historyDeleteConfirm.focus());
    });

    historyDeleteCancel?.addEventListener('click', () => closeDeleteHistoryModal(false));
    historyDeleteConfirm?.addEventListener('click', () => closeDeleteHistoryModal(true));
    historyDeleteModal?.addEventListener('click', event => {
        if (event.target === historyDeleteModal) closeDeleteHistoryModal(false);
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && historyDeleteModal?.classList.contains('visible')) {
            event.preventDefault();
            closeDeleteHistoryModal(false);
        }
    });

    const deleteHistoryEntry = async (entry, rowElement) => {
        const userId = currentUserId || userIdField.value.trim() || '';
        const title = entry.title || 'this history item';
        const confirmed = await askDeleteHistoryConfirmation(entry);
        if (!confirmed) return;

        try {
            const params = new URLSearchParams({ userId });
            if (entry.kind === 'db' && !entry.legacy) {
                params.set('tripId', String(entry.id));
            } else if (entry.threadId) {
                params.set('sessionId', String(entry.threadId));
            }

            // A local-only chat has no server session. It is removed from
            // browser history without making a pointless DB request.
            const hasServerTarget = params.has('tripId') || params.has('sessionId');
            if (hasServerTarget) {
                const res = await apiFetch('/api/history?' + params.toString(), { method: 'DELETE' });
                const data = await res.json().catch(() => ({}));
                if (!res.ok) throw new Error(data.error || 'Could not delete history');
            }

            const store = loadStore();
            const threadId = entry.threadId || '';
            store.chats = (store.chats || []).filter(chat => {
                if (chat.id === entry.id) return false;
                if (threadId && chatThreadIdForDelete(chat) === threadId) return false;
                return true;
            });
            saveStore(store);

            if (entry.kind === 'db') {
                dbTrips = dbTrips.filter(trip => String(trip.id) !== String(entry.id));
            }

            if ((entry.kind === 'chat' && entry.id === currentChatId) || currentHistoryKey === entry.kind + ':' + String(entry.id)) {
                currentChatId = null;
                currentHistoryKey = null;
                startedFreshChat = true;
                clearThreadDom();
                promptInput.value = '';
                
    resizePrompt();
            }

            renderHistoryList();
            renderDbTripsView();
            showToast('Deleted from Recent History and memory database.');
        } catch (error) {
            console.warn('Could not delete history', error);
            showToast(error?.message || 'Could not delete history.');
        }
    };

    const chatThreadIdForDelete = (chat) => {
        if (chat?.threadId) return String(chat.threadId);
        for (const message of (chat?.messages || [])) {
            const id = message?.planData?.threadId;
            if (id) return String(id);
        }
        return '';
    };

    const clearThreadDom = () => {
        chatThread.querySelectorAll('.chat-msg').forEach(node => node.remove());
        layout.classList.remove('has-chat');
        if (!chatThread.querySelector('.chat-empty')) {
            const empty = document.createElement('div');
            empty.className = 'chat-empty';
            empty.textContent = 'Your trip plans will appear here and stay as you chat.';
            chatThread.appendChild(empty);
        }
    };

    const openChat = async (chatId) => {
        exitModifyMode(false);
        showHomeView();
        const store = loadStore();
        const chat = store.chats.find(c => c.id === chatId);
        if (!chat) return;

        currentChatId = chatId;
        currentHistoryKey = 'chat:' + String(chatId);
        renderHistoryList();
        startedFreshChat = String(chatId).startsWith('chat-');
        clearThreadDom();

        let lastUser = '';
        let fallbackRestore = null;
        // A chat created in the current browser starts with chat-..., but once
        // the backend creates the LangGraph thread it is still the same chat.
        // Treat ANY chat with a real threadId as server-backed. Otherwise we
        // render stale/local planData and lose structured Weather/Hotel/RAG/etc.
        // when the user switches chats and comes back.
        const sessionId = chatThreadIdForDelete(chat);
        const isServerChat = String(chatId).startsWith('server-') || !!sessionId;

        // Always refresh a server conversation before rendering it. This makes
        // Recent History independent of stale localStorage and restores the
        // exact structured result saved by the backend.
        let messages = chat.messages || [];
        if (isServerChat && sessionId) {
            try {
                const userId = currentUserId || userIdField.value.trim() || '';
                const res = await apiFetch('/api/chat/session/' + encodeURIComponent(sessionId)
                    + '?userId=' + encodeURIComponent(userId) + '&limit=100');
                if (res.ok) {
                    const serverMessages = await res.json();
                    if (Array.isArray(serverMessages) && serverMessages.length) {
                        messages = serverMessages.map(msg => {
                            let planData = null;
                            if (msg.structuredData) {
                                try { planData = JSON.parse(msg.structuredData); } catch (ignored) {}
                            }
                            return {
                                role: msg.role,
                                text: msg.content || '',
                                planData
                            };
                        });
                    }
                }
            } catch (e) {
                console.warn('Could not refresh server conversation', e);
            }
        }

        // Old V13/V14 server rows may have no structuredData. Recover the
        // latest response directly from the LangGraph checkpoint instead of
        // falling back to the generic "Travel information" text card.
        const needsRestore = isServerChat && sessionId
            && messages.some(m => m.role === 'assistant' && !m.planData);
        if (needsRestore) {
            try {
                const userId = currentUserId || userIdField.value.trim() || '';
                const restoreRes = await apiFetch('/api/plan/' + encodeURIComponent(sessionId)
                    + '/restore?userId=' + encodeURIComponent(userId));
                if (restoreRes.ok) {
                    fallbackRestore = await restoreRes.json();
                }
            } catch (e) {
                console.warn('Could not restore structured history result', e);
            }
        }

        let restoredApplied = false;
        messages.forEach(msg => {
            if (msg.role === 'user') {
                lastUser = msg.text || '';
                renderUserMessage(lastUser, false);
            } else {
                let planData = msg.planData;
                if (!planData && fallbackRestore && !restoredApplied) {
                    planData = fallbackRestore;
                    restoredApplied = true;
                }
                renderAssistantMessage(planData || {
                    status: '',
                    finalPlan: msg.text,
                    awaitingApproval: false,
                    pipeline: []
                }, false, lastUser);
            }
        });
        renderHistoryList();
        scrollChat();
    };

    newChatBtn.addEventListener('click', () => {
        exitModifyMode(true);
        startedFreshChat = true;
        currentChatId = null;
        currentHistoryKey = null;
        ensureCurrentChat('New trip');
        clearThreadDom();
        renderHistoryList();
        promptInput.value = '';
        
    resizePrompt();
        loadingState.classList.remove('visible');
        submitButton.disabled = false;
        promptInput.focus();
    });

    if (composerNewChat) {
        composerNewChat.addEventListener('click', () => {
            newChatBtn.click();
        });
    }

    promptInput.addEventListener('input', () => {
        
    resizePrompt();
        updateComposerState();
        requestAnimationFrame(resizePrompt);
    });

    // Recalculate after fonts/layout settle and when the viewport changes.
    window.addEventListener('resize', resizePrompt);

    const markChatActive = () => {
        layout.classList.add('has-chat');
        const empty = chatThread.querySelector('.chat-empty');
        if (empty) {
            empty.remove();
        }
    };

    const scrollChat = () => {
        const pane = messagesPane || chatThread;
        pane.scrollTop = pane.scrollHeight;
    };

    const escapeHtml = (value) => String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');

    const formatInr = (amount) => {
        if (amount == null || amount === '') {
            return '—';
        }
        const num = Number(amount);
        if (Number.isNaN(num)) {
            return String(amount);
        }
        return '₹' + num.toLocaleString('en-IN', { maximumFractionDigits: 0 });
    };

    const formatDateRange = (start, end) => {
        const fmt = (iso) => {
            if (!iso) {
                return '';
            }
            const d = new Date(iso + 'T12:00:00');
            return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
        };
        if (start && end) {
            return fmt(start) + ' – ' + fmt(end);
        }
        return fmt(start || end) || 'Dates TBD';
    };

    const tripEmoji = (destination) => {
        const d = (destination || '').toLowerCase();
        if (d.includes('japan') || d.includes('tokyo')) return '🇯🇵';
        if (d.includes('paris') || d.includes('france')) return '🇫🇷';
        if (d.includes('bangkok') || d.includes('thailand')) return '🇹🇭';
        if (d.includes('dubai')) return '🇦🇪';
        return '✈️';
    };

    const tripTitle = (data) => {
        const trip = data.plan && data.plan.trip;
        if (trip && trip.title) {
            return trip.title;
        }
        const dest = (data.destination || (trip && trip.destination) || 'Trip').trim();
        if (dest.toLowerCase().includes('japan')) {
            return 'Japan Trip';
        }
        return dest + ' Trip';
    };

    const audienceLabel = (data, userRequest) => {
        const trip = data.plan && data.plan.trip;
        if (trip && trip.audienceLabel) {
            return trip.audienceLabel;
        }
        const text = ((userRequest || '') + ' ' + (data.travelStyle || '')).toLowerCase();
        if (text.includes('family')) {
            return '👨‍👩‍👧‍👦 Family';
        }
        if (text.includes('couple')) {
            return '💑 Couple';
        }
        if (text.includes('solo')) {
            return '🧳 Solo';
        }
        return data.travelers > 1 ? '👤 ' + data.travelers + ' travelers' : '👤 1 traveler';
    };

    const sectionBlock = (title, bodyHtml, emptyMessage) => {
        if (!bodyHtml && !emptyMessage) return '';
        const content = bodyHtml || '<p class="result-empty">' + escapeHtml(emptyMessage || 'Not available.') + '</p>';
        return '<section class="result-panel"><div class="result-panel-title">' + title + '</div><div>' + content + '</div></section>';
    };

    const formatKnowledgeText = (text) => {
        if (!text) return '';
        const safe = escapeHtml(String(text).trim());
        const paragraphs = safe.split(/\n\s*\n/).map(x => x.trim()).filter(Boolean);
        if (!paragraphs.length) return '';
        return paragraphs.map(p => {
            if (/^(?:[-*•]\s+)/m.test(p)) {
                const items = p.split(/\n/).map(x => x.replace(/^(?:[-*•]\s+)/,'').trim()).filter(Boolean);
                return '<ul>' + items.map(i => '<li>' + i + '</li>').join('') + '</ul>';
            }
            return '<p>' + p.replace(/\n/g, '<br>') + '</p>';
        }).join('');
    };

    const buildBudgetTable = (budget) => {
        if (!budget || !budget.lineItems || !budget.lineItems.length) return '';
        const rows = budget.lineItems.map(item =>
            '<div class="budget-card"><div class="label">' + escapeHtml(item.category || 'Item') + '</div><div class="amount">' + formatInr(item.amountInr) + '</div></div>').join('');
        let html = '<div class="budget-grid">' + rows + '</div>';
        if (budget.estimatedCost != null) {
            html += '<div class="budget-total-card"><span>Total estimated</span><strong>' + formatInr(budget.estimatedCost) + '</strong></div>';
        }
        return html;
    };

    const buildPlanReview = (data, userRequest) => {
        const validation = data.plan && data.plan.validation;
        const warnings = (data.semanticNotes || []).concat(data.validationErrors || []);
        if (validation && validation.reviewItems && validation.reviewItems.length) {
            const items = validation.reviewItems.map(item => (item.level === 'warn' ? '⚠ ' : '✓ ') + (item.text || '')).join(' · ');
            return '<div class="validation-banner">' + escapeHtml(items) + '</div>';
        }
        if (warnings.length) return '<div class="validation-banner">⚠ ' + escapeHtml(warnings.join(' · ')) + '</div>';
        if (data.ragJudge && data.ragJudge !== 'PASS') return '<div class="validation-banner">⚠ ' + escapeHtml(String(data.ragJudge)) + '</div>';
        return '<div class="result-chip success">✓ Validation passed</div>';
    };

    const activityTags = (activity) => {
        const tags = [];
        if (activity.indoorOutdoor && activity.indoorOutdoor !== 'mixed') tags.push(activity.indoorOutdoor);
        if (activity.familyFriendly) tags.push('family-friendly');
        if (activity.foodExperience) tags.push('food');
        if (activity.localExperience) tags.push('local');
        return tags;
    };

    const buildFlightsSection = (flights) => {
        const usable = (flights || []).filter(f => f && String(f.status || '').toLowerCase() !== 'unavailable');
        if (!usable.length) return '<div class="specialist-empty">No confirmed flight options are available for the requested route/date.</div>';

        const escape = value => escapeHtml(value == null ? '' : String(value));
        const initials = value => {
            const words = String(value || 'Flight').trim().split(/\s+/).filter(Boolean);
            return words.slice(0, 2).map(w => w[0]).join('').toUpperCase() || '✈';
        };
        const firstMatch = (text, regex) => {
            const match = String(text || '').match(regex);
            return match && match[1] ? match[1].trim() : '';
        };
        const noteText = f => String(f.notes || '');
        const price = f => f.price != null ? String(f.price) : firstMatch(noteText(f), /(?:price|fare)\s*[=:]\s*([^,;|]+)/i);
        const currency = f => f.currency || firstMatch(noteText(f), /(?:currency)\s*[=:]\s*([A-Z]{3}|₹|\$|€|£)/i) || '';
        const provider = f => f.provider || firstMatch(noteText(f), /provider\s*[=:]\s*([^,;|]+)/i) || '';
        const segmentCount = f => {
            const raw = f.segmentCount != null ? f.segmentCount : firstMatch(noteText(f), /segments?\s*[=:]\s*(\d+)/i);
            const n = Number(raw);
            return Number.isFinite(n) && n > 0 ? n : 1;
        };
        const selfTransfer = f => f.selfTransfer === true || /selfTransfer\s*[=:]\s*true/i.test(noteText(f));
        const ignavId = f => f.ignavId || firstMatch(noteText(f), /ignavId\s*[=:]\s*([A-Za-z0-9_-]+)/i);
        const isIso = value => /^\d{4}-\d{2}-\d{2}T/.test(String(value || ''));
        const formatTime = value => {
            if (!value) return '';
            const text = String(value);
            if (!isIso(text)) return text.length > 24 ? text.slice(0, 24) : text;
            const d = new Date(text);
            if (Number.isNaN(d.getTime())) return text;
            return new Intl.DateTimeFormat(undefined, { hour:'2-digit', minute:'2-digit', hour12:false }).format(d);
        };
        const formatDate = value => {
            if (!value) return '';
            const text = String(value);
            if (!isIso(text)) return text;
            const d = new Date(text);
            if (Number.isNaN(d.getTime())) return text.slice(0, 10);
            return new Intl.DateTimeFormat(undefined, { day:'2-digit', month:'short' }).format(d);
        };
        const duration = (dep, arr) => {
            if (!dep || !arr) return '';
            const a = new Date(dep).getTime(), b = new Date(arr).getTime();
            if (!Number.isFinite(a) || !Number.isFinite(b) || b <= a) return '';
            const mins = Math.round((b-a)/60000), h = Math.floor(mins/60), m = mins%60;
            return h ? h + 'h' + (m ? ' ' + m + 'm' : '') : m + 'm';
        };
        const airport = value => escape(value || '—');
        const cleanNote = f => noteText(f)
            .replace(/provider\s*[=:]\s*[^,;|]+/ig, '')
            .replace(/(?:price|fare)\s*[=:]\s*[^,;|]+/ig, '')
            .replace(/segments?\s*[=:]\s*\d+/ig, '')
            .replace(/selfTransfer\s*[=:]\s*(?:true|false)/ig, '')
            .replace(/ignavId\s*[=:]\s*[A-Za-z0-9_-]+/ig, '')
            .replace(/^[\s·,;|-]+|[\s·,;|-]+$/g, '')
            .replace(/\s{2,}/g, ' ')
            .trim();

        const renderFlight = (f, index) => {
            const direction = String(f.direction || 'outbound').toLowerCase() === 'return' ? 'return' : 'outbound';
            const dep = f.departureTime || f.departureScheduled || '';
            const arr = f.arrivalTime || f.arrivalScheduled || '';
            const airline = f.airline || 'Flight option';
            const flightNo = f.flightNumber || '';
            const route = (f.origin && f.destination) ? f.origin + ' → ' + f.destination : '';
            const dur = f.duration || duration(dep, arr);
            const segments = segmentCount(f);
            const stops = f.stops != null ? Number(f.stops) : Math.max(0, segments - 1);
            const fare = price(f);
            const fareCurrency = currency(f);
            const providerName = provider(f);
            const self = selfTransfer(f);
            const extraClass = index >= 3 ? ' is-extra-flight' : '';
            const dateLabel = f.requestedDate || formatDate(dep);
            const note = cleanNote(f);
            const status = String(f.status || '').toLowerCase();
            const isLive = /live|scheduled|confirmed/.test(status) || /provider\s*[=:]\s*(aviationstack|ignav)/i.test(noteText(f));
            const stopsLabel = stops === 0 ? 'Non-stop' : (stops === 1 ? '1 stop' : stops + ' stops');
            const meta = [dur, stopsLabel, self ? 'Self-transfer' : ''].filter(Boolean);
            return '<article class="rich-flight-card' + extraClass + '" data-flight-index="' + index + '">' 
                + '<div class="flight-airline"><div class="flight-airline-mark">' + escape(initials(airline)) + '</div><div class="flight-airline-copy"><div class="flight-airline-name">' + escape(airline) + '</div><div class="flight-number">' + escape(flightNo || 'Flight details') + '</div></div></div>'
                + '<div class="flight-path">'
                + '<div class="flight-times">'
                + '<div class="flight-time-point"><div class="flight-time">' + escape(formatTime(dep) || '—') + '</div><div class="flight-airport">' + airport(f.origin) + '</div><div class="flight-date">' + escape(dateLabel) + '</div></div>'
                + '<div class="flight-route-line"><span>' + escape(dur || 'journey') + '</span><span class="flight-route-plane">✈</span></div>'
                + '<div class="flight-time-point"><div class="flight-time">' + escape(formatTime(arr) || '—') + '</div><div class="flight-airport">' + airport(f.destination) + '</div><div class="flight-date">' + escape(formatDate(arr)) + '</div></div>'
                + '</div>'
                + '<div class="flight-meta-row">'
                + meta.map((item, i) => '<span class="flight-pill' + (i === 1 && stops > 0 ? ' warn' : '') + '">' + escape(item) + '</span>').join('')
                + (isLive ? '<span class="flight-pill live">● Live schedule</span>' : '')
                + (direction === 'return' ? '<span class="flight-pill">↩ Return</span>' : '<span class="flight-pill">↗ Outbound</span>')
                + (providerName ? '<span class="flight-pill">via ' + escape(providerName) + '</span>' : '')
                + '</div>'
                + '</div>'
                + '<div class="flight-fare">'
                + (fare ? '<div class="flight-price">' + escape(fareCurrency ? fareCurrency + ' ' + fare : fare) + '</div><div class="flight-price-muted">fare returned</div>' : '<div class="flight-price-muted">Schedule only</div><div class="flight-price-muted">fare not returned</div>')
                + (providerName ? '<div class="flight-provider">' + escape(providerName) + '</div>' : '')
                + '</div>'
                + (note ? '<div class="flight-note">' + escape(note) + '</div>' : '')
                + '</article>';
        };

        // Keep this as an object list because Thymeleaf reserves nested bracket syntax.
        // Object literals keep this script valid JavaScript without colliding with Thymeleaf syntax.
        const groups = [
            { key: 'outbound', label: 'Outbound' },
            { key: 'return', label: 'Return' }
        ];
        let total = 0, html = '<div class="flight-rich-widget">';
        groups.forEach(group => {
            const key = group.key;
            const label = group.label;
            const items = usable.filter(f => String(f.direction || 'outbound').toLowerCase() === key);
            if (!items.length) return;
            total += items.length;
            const extraCount = Math.max(0, items.length - 3);
            const listClass = extraCount ? 'flight-list has-extra-flights' : 'flight-list';
            html += '<div class="flight-group">'
                + '<div class="flight-group-title"><span>' + label + '</span><span class="flight-group-count">' + items.length + ' option' + (items.length === 1 ? '' : 's') + '</span></div>'
                + '<div class="' + listClass + '">' + items.map(renderFlight).join('') + '</div>'
                + (extraCount ? '<button type="button" class="flight-more-btn" data-flights-more>＋ ' + extraCount + ' more available option' + (extraCount === 1 ? '' : 's') + '</button>' : '')
                + '</div>';
        });
        html = html.replace('<div class="flight-rich-widget">', '<div class="flight-rich-widget"><div class="flight-summary-bar"><div class="flight-summary-main"><div class="flight-summary-icon">✈</div><div><div class="flight-summary-title">Live flight options</div><div class="flight-summary-sub">Compare departure, arrival, duration, stops and returned fare data</div></div></div><span class="flight-summary-count">' + total + ' option' + (total === 1 ? '' : 's') + '</span></div>');
        return html + '</div>';
    };

    const cleanHotelText = (value) => String(value || '')
        .replace(/^#{1,6}\s*/gm, '')
        .replace(/^[-*]\s*/gm, '')
        .replace(/\s+/g, ' ')
        .trim();

    const isLikelyHotelName = (value) => {
        const name = cleanHotelText(value);
        if (name.length < 2 || name.length > 120) return false;
        const lower = name.toLowerCase();
        if (/https?:\/\//i.test(name) || /\b20\d{2}\b/.test(name)) return false;
        if (/best areas|areas? & hotels?|hotels? to stay|where to stay|top hotels?|hotel guide|accommodation guide|travel guide|things to do|complete guide/.test(lower)) return false;
        if (/^the\s+best\b|^top\s+\d+|\b\d+\s*(best|top|hotels?)\b/.test(lower)) return false;
        if (/\(\s*\d+\s*(best|top|hotels?|options?)?/i.test(name)) return false;
        if ((name.match(/[:;|]/g) || []).length > 1) return false;
        if (name.split(/\s+/).length > 11) return false;
        return true;
    };

    const buildHotelsSection = (hotels) => {
        const usable = (Array.isArray(hotels) ? hotels : [])
            .filter(h => h && String(h.name || '').trim());
        if (!usable.length) return '<div class="specialist-empty">No verified hotel properties were found for this request.</div>';

        const visibleCount = 4;
        const cards = usable.map((h, index) => {
            const name = cleanHotelText(h.name);
            const area = cleanHotelText(h.area);
            const rating = cleanHotelText(h.rating);
            const hotelClass = cleanHotelText(h.hotelClass);
            const price = cleanHotelText(h.priceRange);
            const total = cleanHotelText(h.totalPrice);
            const deal = cleanHotelText(h.deal);
            const note = cleanHotelText(h.notes);
            const amenities = String(h.amenities || '').split(',').map(x => cleanHotelText(x)).filter(Boolean).slice(0, 6);
            const image = String(h.imageUrl || '').trim();
            const booking = String(h.bookingUrl || '').trim();
            const reviews = Number(h.reviews || 0);
            const extraClass = index >= visibleCount ? ' is-extra' : '';
            const badge = deal ? '<span class="hotel-rich-badge deal">' + escapeHtml(deal.length > 28 ? deal.slice(0,28) + '…' : deal) + '</span>' : (hotelClass ? '<span class="hotel-rich-badge">' + escapeHtml(hotelClass) + '</span>' : '');
            const media = image
                ? '<img src="' + escapeHtml(image) + '" alt="' + escapeHtml(name) + '" loading="lazy" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'grid\';"><div class="hotel-rich-media-fallback">🏨</div>'
                : '<div class="hotel-rich-media-fallback">🏨</div>';
            const ratingHtml = rating
                ? '<div class="hotel-rich-rating"><span>★ ' + escapeHtml(rating) + '</span>' + (reviews > 0 ? '<span class="reviews">· ' + escapeHtml(reviews.toLocaleString()) + ' reviews</span>' : '') + (hotelClass ? '<span class="hotel-rich-class">' + escapeHtml(hotelClass) + '</span>' : '') + '</div>'
                : (reviews > 0 ? '<div class="hotel-rich-rating"><span class="reviews">' + escapeHtml(reviews.toLocaleString()) + ' reviews</span></div>' : '');
            const amenityHtml = amenities.length ? '<div class="hotel-rich-amenities">' + amenities.map(a => '<span class="hotel-amenity-chip">✓ ' + escapeHtml(a) + '</span>').join('') + '</div>' : '';
            const dealHtml = deal ? '<div class="hotel-rich-deal">✦ ' + escapeHtml(deal) + '</div>' : '';
            const bookHtml = booking ? '<a class="hotel-rich-book" href="' + escapeHtml(booking) + '" target="_blank" rel="noopener noreferrer">View deal ↗</a>' : '';
            const cancelHtml = h.freeCancellation ? '<div class="hotel-rich-cancel">✓ Free cancellation</div>' : '';
            return '<article class="hotel-rich-card' + extraClass + '">'
                + '<div class="hotel-rich-media">' + media + badge + '</div>'
                + '<div class="hotel-rich-main">'
                + '<div class="hotel-rich-title-row"><div style="min-width:0"><div class="hotel-rich-name">' + escapeHtml(name) + '</div>'
                + (area ? '<div class="hotel-rich-location">📍 ' + escapeHtml(area) + '</div>' : '') + '</div></div>'
                + ratingHtml
                + (note ? '<div class="hotel-rich-note">' + escapeHtml(note) + '</div>' : '')
                + amenityHtml + dealHtml
                + '</div>'
                + '<div class="hotel-rich-price">'
                + (price ? '<div class="hotel-rich-price-label">FROM / NIGHT</div><div class="hotel-rich-price-main">' + escapeHtml(price) + '</div>' : '<div class="hotel-rich-price-label">PRICE</div><div class="hotel-rich-price-main">Check rates</div>')
                + (total ? '<div class="hotel-rich-price-total">Total stay ' + escapeHtml(total) + '</div>' : '')
                + bookHtml + cancelHtml
                + '</div></article>';
        }).join('');

        const extraCount = Math.max(0, usable.length - visibleCount);
        const more = extraCount > 0
            ? '<button type="button" class="hotel-rich-more" data-hotels-more>＋ ' + extraCount + ' more hotel option' + (extraCount === 1 ? '' : 's') + '</button>'
            : '';
        return '<div class="hotel-rich-widget">'
            + '<div class="hotel-rich-summary"><div class="hotel-rich-summary-main"><div class="hotel-rich-summary-icon">🏨</div><div><div class="hotel-rich-summary-title">Live hotel options</div><div class="hotel-rich-summary-sub">Google Hotels results · prices, ratings, photos, amenities and booking links</div></div></div><span class="hotel-rich-count">' + usable.length + ' option' + (usable.length === 1 ? '' : 's') + '</span></div>'
            + '<div class="hotel-rich-list">' + cards + '</div>' + more + '</div>';
    };
    const buildItinerarySection = (itinerary) => {
        const days = itinerary && Array.isArray(itinerary.days) ? itinerary.days : [];
        if (!days.length) return '';
        const text = value => value == null ? '' : String(value).trim();
        const safeUrl = value => { const url = text(value); return /^https?:\/\//i.test(url) ? url : ''; };
        const money = (value, currency) => { const v = text(value); if (!v) return ''; const c = text(currency); return c ? c + ' ' + v : v; };
        const activityIcon = type => {
            const t = text(type).toLowerCase();
            if (t.includes('food') || t.includes('restaurant') || t.includes('dining')) return '🍜';
            if (t.includes('culture') || t.includes('museum') || t.includes('history')) return '🏛️';
            if (t.includes('nature') || t.includes('park') || t.includes('scenic')) return '🌿';
            if (t.includes('transport') || t.includes('airport')) return '✈️';
            if (t.includes('lodging') || t.includes('hotel')) return '🏨';
            if (t.includes('shopping')) return '🛍️';
            if (t.includes('leisure') || t.includes('walk')) return '🚶';
            return '📍';
        };
        let html = '<div class="itin-rich-list">';
        if (text(itinerary.summary)) {
            html += '<div class="itin-overview"><div class="itin-overview-icon">🗺️</div><div style="flex:1"><div class="itin-overview-title">Day-by-day itinerary' + (itinerary.provider ? ' <span class="itin-provider-badge">' + escapeHtml(itinerary.provider) + '</span>' : '') + '</div><div class="itin-overview-text">' + escapeHtml(itinerary.summary) + '</div></div></div>';
        }
        days.forEach(day => {
            const activities = Array.isArray(day.activities) ? day.activities.filter(a => a && text(a.name)) : [];
            const dayCost = money(day.estimatedCost, day.currency);
            const foodCount = activities.filter(a => a.foodExperience === true).length;
            const localCount = activities.filter(a => a.localExperience === true).length;
            const familyCount = activities.filter(a => a.familyFriendly === true).length;
            const stats = '<div class="itin-day-stats">'
                + '<span>📍 ' + activities.length + ' stop' + (activities.length === 1 ? '' : 's') + '</span>'
                + (foodCount ? '<span>🍜 ' + foodCount + ' food</span>' : '')
                + (localCount ? '<span>🏘 ' + localCount + ' local</span>' : '')
                + (familyCount ? '<span>👨‍👩‍👧 family</span>' : '')
                + '</div>';
            html += '<article class="itin-rich-day"><div class="itin-rich-day-head">'
                + '<div class="itin-rich-day-number">DAY ' + escapeHtml(String(day.day || '')) + '</div>'
                + '<div class="itin-rich-day-title-wrap"><h3>' + escapeHtml(text(day.title) || 'Explore') + '</h3>'
                + (text(day.summary) ? '<p>' + escapeHtml(day.summary) + '</p>' : '') + stats + '</div>'
                + (dayCost ? '<div class="itin-day-cost">' + escapeHtml(dayCost) + '<small>estimated</small></div>' : '')
                + '</div>';
            if (activities.length) {
                html += '<div class="itin-rich-activities">';
                activities.forEach((a, activityIndex) => {
                    const tags = activityTags(a);
                    const image = safeUrl(a.imageUrl || a.image || a.photoUrl);
                    const booking = safeUrl(a.bookingUrl || a.url || a.link);
                    const cost = money(a.estimatedCost || a.cost || a.price, a.currency);
                    const location = text(a.location || a.address || a.area);
                    const duration = text(a.duration || a.durationText);
                    const description = text(a.description || a.details || a.notes);
                    html += '<div class="itin-rich-activity">'
                        + '<div class="itin-rich-activity-media">'
                        + (image ? '<img src="' + escapeHtml(image) + '" alt="' + escapeHtml(a.name) + '" loading="lazy" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\';">' : '')
                        + '<div class="itin-rich-activity-placeholder"' + (image ? ' style="display:none"' : '') + '>' + activityIcon(a.type) + '</div>'
                        + '</div><div class="itin-rich-activity-body">'
                        + '<div class="itin-rich-activity-top"><div><div class="itin-rich-activity-name"><span class="itin-stop-index">' + (activityIndex + 1) + '</span>' + escapeHtml(a.name) + '</div>'
                        + (a.type ? '<span class="itin-rich-type">' + escapeHtml(a.type) + '</span>' : '') + '</div>'
                        + (cost ? '<div class="itin-activity-cost">' + escapeHtml(cost) + '</div>' : '') + '</div>'
                        + (description ? '<div class="itin-rich-description">' + escapeHtml(description) + '</div>' : '')
                        + '<div class="itin-rich-meta">'
                        + (location ? '<span>📍 ' + escapeHtml(location) + '</span>' : '')
                        + (duration ? '<span>⏱ ' + escapeHtml(duration) + '</span>' : '')
                        + (a.indoorOutdoor && String(a.indoorOutdoor).toLowerCase() !== 'mixed' ? '<span>◉ ' + escapeHtml(a.indoorOutdoor) + '</span>' : '')
                        + '</div>'
                        + (tags.length ? '<div class="itin-rich-tags">' + tags.map(tag => '<span>✓ ' + escapeHtml(tag) + '</span>').join('') + '</div>' : '')
                        + (booking ? '<a class="itin-book-btn" href="' + escapeHtml(booking) + '" target="_blank" rel="noopener noreferrer">View / book ↗</a>' : '')
                        + '</div></div>';
                });
                html += '</div>';
            } else {
                html += '<div class="itin-rich-empty">No activities were returned for this day.</div>';
            }
            html += '</article>';
        });
        return html + '</div>';
    };

    const buildWeatherSection = (weather, options = {}) => {
        if (!weather) return '';
        const current = weather.current || {};
        const days = Array.isArray(weather.days) ? weather.days.filter(Boolean).slice(0, 7) : [];
        const hasCurrent = current.temperature != null;
        const hasForecast = days.some(day => day && (day.high != null || day.low != null || day.condition));
        const hasWeatherData = hasCurrent || hasForecast;
        const focused = !!options.focused;
        if (!hasWeatherData) {
            const location = weather.location || 'the requested destination';
            return '<div class="weather-dashboard-card weather-empty-state">'
                + '<div class="weather-dashboard-head"><div><div class="weather-location-title">🌤️ Weather</div>'
                + '<div class="weather-location-sub">' + escapeHtml(location) + '</div></div>'
                + '<span class="weather-live-badge">OpenWeather</span></div>'
                + '<div class="weather-empty-message"><div class="weather-empty-icon">☁️</div>'
                + '<div><div class="weather-empty-title">No weather details found</div>'
                + '<div class="weather-empty-detail">We could not retrieve usable weather details for ' + escapeHtml(location) + '.</div></div></div>'
                + '</div>';
        }
        const fmtDate = (value) => {
            if (!value) return '';
            const d = new Date(value + (String(value).length === 10 ? 'T00:00:00' : ''));
            return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleDateString(undefined, { weekday:'short', month:'short', day:'numeric' });
        };
        const shortDate = (value) => {
            if (!value) return '';
            const d = new Date(value + (String(value).length === 10 ? 'T00:00:00' : ''));
            return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleDateString(undefined, { month:'short', day:'numeric' });
        };
        const dayKey = (value) => String(value || '').slice(0,10);
        const todayKey = new Date().toISOString().slice(0,10);
        const fmtTime = (unix) => {
            if (unix == null || unix === '') return '—';
            const d = new Date(Number(unix) * 1000);
            return Number.isNaN(d.getTime()) ? '—' : d.toLocaleTimeString(undefined, { hour:'numeric', minute:'2-digit' });
        };
        const num = (value, digits = 0) => value == null || value === '' || Number.isNaN(Number(value)) ? '—' : Number(value).toFixed(digits);
        const km = (meters) => meters == null ? '—' : (Number(meters) / 1000).toFixed(1) + ' km';
        const windDir = (deg) => {
            if (deg == null || Number.isNaN(Number(deg))) return '—';
            const dirs = ['N','NE','E','SE','S','SW','W','NW'];
            return dirs[Math.round(Number(deg) / 45) % 8] + ' · ' + Math.round(Number(deg)) + '°';
        };
        const weatherIcon = (icon, cls) => /^[0-9]{2}[dn]$/.test(String(icon || ''))
            ? '<img class="' + cls + '" src="https://openweathermap.org/img/wn/' + escapeHtml(String(icon)) + '@2x.png" alt="Weather icon">'
            : '<span class="' + cls + '" style="display:flex;align-items:center;justify-content:center;font-size:30px">🌤️</span>';
        const dateRange = days.length ? shortDate(days[0].date) + (days.length > 1 ? ' – ' + shortDate(days[days.length - 1].date) : '') : '';
        let html = '<div class="weather-dashboard-card' + (focused ? ' weather-focused' : '') + '">';
        html += '<div class="weather-dashboard-head"><div><div class="weather-location-title">🌤️ ' + escapeHtml(weather.location || 'Destination') + ' Weather</div>'
            + '<div class="weather-location-sub">' + escapeHtml(dateRange || 'Current conditions') + '</div></div>'
            + '<span class="weather-live-badge">OpenWeather · Live</span></div>';

        if (hasCurrent) {
            const desc = current.description || current.condition || 'Current conditions';
            const rainNow = current.rain1h != null ? Number(current.rain1h).toFixed(1) + ' mm/h' : 'None reported';
            const snowNow = current.snow1h != null ? Number(current.snow1h).toFixed(1) + ' mm/h' : 'None reported';
            html += '<div class="weather-current">'
                + '<div class="weather-current-main"><div class="weather-current-label">Current conditions</div>'
                + '<div class="weather-current-condition">' + weatherIcon(current.icon, 'weather-current-icon') + '<div><div class="weather-current-temp">' + num(current.temperature) + '°<small>C</small></div><div class="weather-current-desc">' + escapeHtml(desc) + '</div></div></div>'
                + '<div class="weather-current-feels">Feels like ' + num(current.feelsLike) + '°C · Updated ' + escapeHtml(fmtTime(current.observedAt)) + '</div></div>'
                + '<div><div class="weather-metrics">'
                + metric('💧','Humidity', current.humidity != null ? Math.round(Number(current.humidity)) + '%' : '—')
                + metric('🌡️','Pressure', current.pressure != null ? Math.round(Number(current.pressure)) + ' hPa' : '—')
                + metric('💨','Wind gust', current.windGust != null ? num(current.windGust,1) + ' m/s' : '—')
                + metric('☁️','Cloud cover', current.clouds != null ? Math.round(Number(current.clouds)) + '%' : '—')
                + metric('💨','Wind', current.windSpeed != null ? num(current.windSpeed,1) + ' m/s' : '—', windDir(current.windDeg))
                + metric('👁️','Visibility', km(current.visibilityMeters))
                + metric('🌧️','Rain · 1h', rainNow)
                + metric('❄️','Snow · 1h', snowNow)
                + '</div><div class="weather-sunline">'
                + '<div class="weather-sun">🌅 Sunrise<strong>' + escapeHtml(fmtTime(current.sunrise)) + '</strong></div>'
                + '<div class="weather-sun">🌇 Sunset<strong>' + escapeHtml(fmtTime(current.sunset)) + '</strong></div>'
                + '<div class="weather-sun">🌎 Timezone<strong>' + escapeHtml(current.timezone || 'Local') + '</strong></div>'
                + '</div></div></div>';
            html += '<div class="weather-source">Source: <strong>OpenWeather Free · Current Weather + 5-day / 3-hour Forecast</strong> · Provider values are displayed directly; recommendations may be generated separately.</div>';
        }

        if (days.length) {
            const wet = days.reduce((best, day) => Number(day.rainProbability || 0) > Number(best?.rainProbability || 0) ? day : best, null);
            const maxRain = Number(wet?.rainProbability || 0);
            html += '<div class="weather-outlook">'
                + '<div class="weather-outlook-head"><div><div class="weather-outlook-title">Travel-date outlook</div>'
                + '<div class="weather-outlook-sub">A quick view of conditions across your travel window</div></div>'
                + '<div class="weather-outlook-count">' + days.length + ' day' + (days.length === 1 ? '' : 's') + '</div></div>'
                + '<div class="weather-days-grid">';
            html += days.map((day, index) => {
                const high = day.high != null ? Math.round(Number(day.high)) + '°' : '—';
                const low = day.low != null ? Math.round(Number(day.low)) + '°' : '—';
                const rainValue = day.rainProbability != null ? Math.max(0, Math.min(100, Math.round(Number(day.rainProbability)))) : null;
                const rain = rainValue != null ? rainValue + '%' : '—';
                const isToday = dayKey(day.date) === todayKey || index === 0 && dayKey(day.date) === todayKey;
                const condition = day.condition || 'Forecast';
                let note = 'Good for plans';
                if (rainValue != null && rainValue >= 70) note = 'Plan rain cover';
                else if (rainValue != null && rainValue >= 40) note = 'Keep plans flexible';
                else if (rainValue != null && rainValue < 20) note = 'Great for outdoors';
                return '<div class="weather-day-card' + (isToday ? ' today' : '') + '">'
                    + '<div class="weather-day-top"><div class="weather-day-date">' + escapeHtml(fmtDate(day.date)) + '</div>'
                    + (isToday ? '<span class="weather-day-badge">Today</span>' : '') + '</div>'
                    + '<div class="weather-day-main">' + weatherIcon(day.icon, 'weather-day-icon') + '<div class="weather-day-condition">' + escapeHtml(condition) + '</div></div>'
                    + '<div class="weather-day-temp"><strong>' + escapeHtml(high) + '</strong><span>Low ' + escapeHtml(low) + '</span></div>'
                    + '<div class="weather-day-rain"><span>🌧 Rain chance</span><strong>' + escapeHtml(rain) + '</strong></div>'
                    + (rainValue != null ? '<div class="weather-rain-track"><div class="weather-rain-fill" style="width:' + rainValue + '%"></div></div>' : '')
                    + '<div class="weather-day-note">' + escapeHtml(note) + '</div></div>';
            }).join('');
            html += '</div>';
            let insight = '';
            if (maxRain >= 70) insight = 'Rain is most likely around ' + shortDate(wet.date) + '. Consider indoor attractions or keep a flexible backup plan.';
            else if (maxRain >= 40) insight = 'There is a moderate rain risk in the travel window. Outdoor plans should stay flexible.';
            else insight = 'The forecast looks broadly comfortable for sightseeing, with lower precipitation risk across the travel window.';
            html += '<div class="weather-insight"><span class="weather-insight-icon">💡</span><div><strong>Travel Weather Insight</strong><div>' + escapeHtml(insight) + '</div></div></div>';
            html += '</div>';
        }
        return html + '</div>';

        function metric(icon, label, value, sub) {
            return '<div class="weather-metric"><div class="weather-metric-label">' + icon + ' ' + escapeHtml(label) + '</div><div class="weather-metric-value">' + escapeHtml(String(value)) + '</div>' + (sub ? '<div class="weather-metric-sub">' + escapeHtml(String(sub)) + '</div>' : '') + '</div>';
        }
    };
    const buildAgentDetails = (data) => {
        const exec = data.execution || {};
        const rawSteps = exec.timeline || data.executionTimeline || data.pipeline || [];
        const stepsArr = dedupeSteps(rawSteps).slice(-16);
        const sourcesArr = [...new Set(exec.sources || data.sources || [])];
        const history = exec.executionHistory || data.executionHistory || '';
        const ragUsed = !!(exec.ragUsed || data.ragUsed);
        const ragAnswer = exec.ragAnswer || data.ragAnswer || '';
        const ragSources = exec.ragSources || data.ragSources || [];
        const ragQuery = exec.ragQuery || data.ragQuery || '';
        const ragDecision = exec.ragDecision || data.ragDecision || '';
        const ragMethod = exec.ragRetrievalMethod || data.ragRetrievalMethod || '';
        const ragScore = exec.ragEvidenceScore ?? data.ragEvidenceScore;
        const ragCandidates = exec.ragCandidateCount ?? data.ragCandidateCount;
        const ragReranked = exec.ragRerankedCount ?? data.ragRerankedCount;
        const ragIterations = exec.ragIterations ?? data.ragIterations;
        if (!stepsArr.length && !sourcesArr.length && !history && !ragUsed) return '';
        let body = '<div class="agent-details-body">';
        if (ragUsed) {
            body += '<div class="agent-subsection rag-observability"><div class="agent-subtitle">🧠 RAG result</div>';
            if (ragAnswer) {
                body += '<div class="rag-answer-preview">' + formatKnowledgeText(ragAnswer) + '</div>';
            } else {
                body += '<div class="rag-empty">RAG retrieval completed, but no answer was generated.</div>';
            }
            const meta = [];
            if (ragDecision) meta.push(ragDecision);
            if (ragMethod) meta.push(ragMethod);
            if (Number.isFinite(Number(ragScore))) meta.push('evidence ' + Number(ragScore).toFixed(2));
            if (Number.isFinite(Number(ragCandidates))) meta.push('candidates ' + ragCandidates);
            if (Number.isFinite(Number(ragReranked))) meta.push('reranked ' + ragReranked);
            if (Number.isFinite(Number(ragIterations))) meta.push('iteration ' + ragIterations);
            if (meta.length) body += '<div class="item-meta">' + escapeHtml(meta.join(' · ')) + '</div>';
            if (ragQuery) body += '<div class="rag-query">Query: ' + escapeHtml(ragQuery) + '</div>';
            if (ragSources.length) body += '<div class="agent-subtitle rag-sources-title">Sources</div><pre class="agent-pre">' + escapeHtml(ragSources.join('\n')) + '</pre>';
            body += '</div>';
        }
        if (stepsArr.length) {
            body += '<div class="agent-subsection"><div class="agent-subtitle">Execution</div><div class="execution-mini">'
                + stepsArr.map(step => '<span class="execution-pill">' + escapeHtml(formatStepLine(step)) + '</span>').join('') + '</div></div>';
        }
        if (sourcesArr.length) body += '<div class="agent-subsection"><div class="agent-subtitle">Sources</div><pre class="agent-pre">' + escapeHtml(sourcesArr.join('\n')) + '</pre></div>';
        if (history) body += '<div class="agent-subsection"><div class="agent-subtitle">History</div><pre class="agent-pre">' + escapeHtml(history) + '</pre></div>';
        body += '</div>';
        return '<details class="agent-details"><summary>🔎 Agent execution & sources</summary>' + body + '</details>';
    };

    const safeUiErrorMessage = () =>
        "We couldn\'t complete your travel plan. Please try again.";

    const humanizeKnowledgeSource = (source) => {
        const raw = String(source || '').trim();
        if (!raw) return '';
        return raw
            .replace(/^.*[\\/]/, '')
            .replace(/\\.(md|txt|pdf)$/i, '')
            .replace(/[-_]+/g, ' ')
            .replace(/\\b\\w/g, ch => ch.toUpperCase());
    };

    const isInternalOrPlanKnowledge = (text) => {
        const value = String(text || '').trim();
        if (!value) return true;
        return /llm\s+budget\s+exhausted|budget\s+exhausted|quota(?:\s+exceeded)?|rate\s*limit|model unavailable|stack trace/i.test(value)
            || /(?:^|\n)\s*(?:#{1,6}\s*)?(?:flights?|hotels?|accommodation|itinerary|day[- ]by[- ]day|trip plan)\s*[:#-]?/i.test(value)
            || /(?:₹|\$|€)\s*[\d,]+/i.test(value)
            || /\b(?:flight|airline|hotel|accommodation|trip total|budget breakdown|fare)\b/i.test(value);
    };

    const safeKnowledgeAnswer = (text) => {
        const value = String(text || '').trim();
        if (!value || isInternalOrPlanKnowledge(value) || value.length > 1600) return '';
        return value;
    };

    const safeTripTips = (text) => {
        const value = String(text || '').trim();
        if (!value || /llm\s+budget\s+exhausted|budget\s+exhausted|quota(?:\s+exceeded)?|rate\s*limit|model unavailable|stack trace/i.test(value)) return '';
        return value.length > 1400 ? value.slice(0, 1397).trim() + '…' : value;
    };

    const buildKnowledgeGuidanceSection = (data, plan) => {
        const exec = data.execution || {};
        const knowledge = plan.knowledge || {};
        const answer = safeKnowledgeAnswer(knowledge.answer || exec.ragAnswer || data.ragAnswer || '');
        const available = knowledge.available === true || !!answer;
        if (!available || !answer) return '';
        const topics = Array.isArray(knowledge.topics) ? knowledge.topics : [];
        const sources = Array.isArray(knowledge.sources) ? knowledge.sources : (Array.isArray(exec.ragSources) ? exec.ragSources : []);
        const destination = String(knowledge.destination || '').trim();
        const title = String(knowledge.title || (destination ? 'Travel Knowledge & Guidance for ' + destination : 'Travel Knowledge & Guidance')).trim();
        const bullets = formatKnowledgeText(answer);
        return '<section id="section-knowledge" class="workspace-card full knowledge-guidance-card compact-knowledge-card">'
            + '<div class="workspace-card-head"><div><div class="workspace-card-title">🧠 ' + escapeHtml(title) + '</div>'
            + '<div class="workspace-card-sub">Durable destination guidance — separate from live trip results</div></div>'
            + '<span class="knowledge-guidance-badge">✓ Grounded</span></div>'
            + (topics.length ? '<div class="knowledge-topics">' + topics.slice(0, 5).map(topic => '<span class="knowledge-topic">' + escapeHtml(String(topic).replace(/[_-]+/g, ' ')) + '</span>').join('') + '</div>' : '')
            + '<div class="workspace-content knowledge-answer compact-knowledge-answer">' + bullets + '</div>'
            + (sources.length ? '<details class="knowledge-sources-details"><summary>View knowledge sources</summary><div class="knowledge-sources">' + sources.slice(0, 5).map(source => '<span class="knowledge-source">' + escapeHtml(humanizeKnowledgeSource(source)) + '</span>').join('') + '</div></details>' : '')
            + '</section>';
    };

    const buildSpecialistKnowledgeCard = (data, plan) => {
        const exec = data.execution || {};
        const knowledge = plan.knowledge || {};
        const answer = safeKnowledgeAnswer(knowledge.answer || exec.ragAnswer || data.ragAnswer || '');
        if (!answer) return '';
        const sources = Array.isArray(knowledge.sources) && knowledge.sources.length
            ? knowledge.sources : (Array.isArray(exec.ragSources) ? exec.ragSources : []);
        const topics = Array.isArray(knowledge.topics) ? knowledge.topics : [];
        let html = '<section class="specialist-card specialist-answer-card knowledge-guidance-card">'
            + '<div class="knowledge-guidance-head"><div class="specialist-card-title">💡 Travel Tips & Guidance</div>'
            + '<span class="knowledge-guidance-badge">✓ Grounded</span></div>';
        if (topics.length) {
            html += '<div class="knowledge-topics">' + topics.slice(0, 8).map(topic =>
                '<span class="knowledge-topic">' + escapeHtml(String(topic).replace(/[_-]+/g, ' ')) + '</span>'
            ).join('') + '</div>';
        }
        html += '<div class="specialist-answer">' + formatKnowledgeText(answer) + '</div>';
        if (sources.length) {
            html += '<details class="knowledge-sources-details"><summary>View knowledge sources</summary><div class="knowledge-sources">' + sources.slice(0, 6).map(source =>
                '<span class="knowledge-source">' + escapeHtml(humanizeKnowledgeSource(source)) + '</span>'
            ).join('') + '</div></details>';
        }
        html += '</section>';
        return html;
    };

    const hasSpecialistKnowledge = (data, plan) => {
        const knowledge = plan?.knowledge || {};
        const exec = data?.execution || {};
        return knowledge.available === true
            || !!String(knowledge.answer || exec.ragAnswer || data?.ragAnswer || '').trim();
    };

    const buildHistoryResponseHtml = (data) => {
        const plan = data?.plan || {};
        const trip = plan.trip || {};
        const route = [trip.origin, trip.destination].filter(Boolean).join(' → ') || 'Saved trip';
        const dates = trip.datesFlexible === true
            ? 'Dates flexible'
            : formatDateRange(trip.departureDate, trip.returnDate);
        const flights = Array.isArray(plan.flights) ? plan.flights : [];
        const hotels = Array.isArray(plan.hotels) ? plan.hotels : [];
        const itinerary = plan.itinerary;
        const weather = plan.weather;
        const budget = plan.budget;
        const tips = safeTripTips(plan.tips || '');
        const quality = Number(trip.qualityScore || 0);
        const requirements = Array.isArray(trip.requirements) ? trip.requirements : [];

        let html = '<div class="history-recalled-dashboard">'
            + '<section class="workspace-card full history-recalled-hero">'
            + '<div class="workspace-card-head"><div><div class="workspace-card-title">🕘 Recalled saved trip</div>'
            + '<div class="workspace-card-sub">Read-only details retrieved from your persistent trip history</div></div>'
            + '<span class="knowledge-guidance-badge">✓ Memory</span></div>'
            + '<div class="history-recalled-route">' + escapeHtml(route) + '</div>'
            + '<div class="history-recalled-meta">'
            + '<span>' + escapeHtml(dates || 'Dates not recorded') + '</span>'
            + (trip.nights > 0 ? '<span>' + escapeHtml(String(trip.nights)) + ' nights</span>' : '')
            + '<span>' + escapeHtml(String(trip.travelers || 1)) + ' traveler' + ((trip.travelers || 1) === 1 ? '' : 's') + '</span>'
            + (trip.budgetLabel ? '<span>' + escapeHtml(trip.budgetLabel) + '</span>' : '')
            + (quality > 0 ? '<span>Confidence ' + escapeHtml(String(quality)) + '/100</span>' : '')
            + '</div>'
            + (requirements.length ? '<div class="knowledge-topics">' + requirements.slice(0, 8).map(r => '<span class="knowledge-topic">' + escapeHtml(String(r).replace(/[_-]+/g, ' ')) + '</span>').join('') + '</div>' : '')
            + '</section>';

        if (flights.length) html += '<section class="workspace-card full"><div class="workspace-card-head"><div><div class="workspace-card-title">✈️ Flights</div><div class="workspace-card-sub">Saved flight details from the trip</div></div></div><div class="workspace-content">' + buildFlightsSection(flights) + '</div></section>';
        if (hotels.length) html += '<section class="workspace-card full"><div class="workspace-card-head"><div><div class="workspace-card-title">🏨 Hotels</div><div class="workspace-card-sub">Saved accommodation details</div></div></div><div class="workspace-content">' + buildHotelsSection(hotels) + '</div></section>';
        if (weather && (weather.summary || weather.location || (Array.isArray(weather.days) && weather.days.length))) html += '<section class="workspace-card full"><div class="workspace-card-head"><div><div class="workspace-card-title">☀️ Weather</div><div class="workspace-card-sub">Saved weather information</div></div></div><div class="workspace-content">' + buildWeatherSection(weather) + '</div></section>';
        if (budget && Array.isArray(budget.lineItems) && budget.lineItems.length) html += '<section class="workspace-card full"><div class="workspace-card-head"><div><div class="workspace-card-title">💰 Budget</div><div class="workspace-card-sub">Saved trip cost estimate</div></div></div><div class="workspace-content">' + buildBudgetTable(budget) + '</div></section>';
        if (itinerary && Array.isArray(itinerary.days) && itinerary.days.length) html += '<section class="workspace-card full"><div class="workspace-card-head"><div><div class="workspace-card-title">🗓️ Day-wise Itinerary</div><div class="workspace-card-sub">Saved itinerary from this trip</div></div></div><div class="workspace-content itin-compact">' + buildItinerarySection(itinerary) + '</div></section>';
        if (plan.knowledge?.available === true || plan.knowledge?.answer) html += buildKnowledgeGuidanceSection(data, plan);
        if (tips) html += '<section class="workspace-card full"><div class="workspace-card-head"><div><div class="workspace-card-title">💡 Travel Tips</div><div class="workspace-card-sub">Saved trip-specific suggestions</div></div></div><div class="workspace-content knowledge-answer">' + formatKnowledgeText(tips) + '</div></section>';

        html += '<section class="workspace-card full history-readonly-note"><div class="workspace-card-title">🔒 Read-only history</div><div class="workspace-card-sub">This is a recalled snapshot. Approve, modify and reject actions are disabled until you create or modify a new plan.</div></section>';
        return html + '</div>';
    };

    const widgetControls = (key, title) =>
        '<div class="widget-controls" aria-label="' + escapeHtml(title) + ' widget controls">'
        + '<button type="button" class="widget-control widget-fold-control" data-widget-action="collapse" data-widget-key="' + escapeHtml(key) + '" title="Fold in ' + escapeHtml(title) + '" aria-label="Fold in ' + escapeHtml(title) + '" aria-expanded="true">⌃</button>'
        + '<button type="button" class="widget-control" data-widget-action="expand" data-widget-key="' + escapeHtml(key) + '" title="Expand ' + escapeHtml(title) + '" aria-label="Expand ' + escapeHtml(title) + '">⛶</button>'
        + '<button type="button" class="widget-control close" data-widget-action="close" data-widget-key="' + escapeHtml(key) + '" title="Hide ' + escapeHtml(title) + '" aria-label="Hide ' + escapeHtml(title) + '">×</button>'
        + '</div>';

    const buildSpecialistResponseHtml = (data, userRequest) => {
        const plan = data.plan || data || {};
        const trip = plan.trip || {};
        const type = String(data.requestType || trip.requestType || 'GENERAL').toUpperCase();
        const location = String((plan.weather && plan.weather.location) || trip.destination || data.destination || '').trim();
        const weather = plan.weather || data.weather;
        const flights = Array.isArray(plan.flights) ? plan.flights : (Array.isArray(data.flights) ? data.flights : []);
        const hotels = Array.isArray(plan.hotels) ? plan.hotels : (Array.isArray(data.hotels) ? data.hotels : []);
        const budget = plan.budget;
        const exec = data.execution || {};
        const answer = String(exec.ragAnswer || data.ragAnswer || plan.tips || data.finalPlan || '').trim();
        const hasWeather = !!(weather && (weather.summary || weather.location || (Array.isArray(weather.days) && weather.days.length) || weather.current));
        const hasBudget = !!(budget && Array.isArray(budget.lineItems) && budget.lineItems.length);
        const hasAnswer = !!answer;
        const clarification = String(data.clarificationRequired || data.plan?.clarificationRequired || '').trim();
        const needsUserInput = String(data.status || '').toUpperCase() === 'NEEDS_USER_INPUT';

        const meta = {
            WEATHER: ['☀️', location ? 'Weather in ' + location : 'Weather details', 'Current conditions and forecast'],
            FLIGHT_SEARCH: ['✈️', location ? 'Flight options for ' + location : 'Flight options', 'Available schedules and returned fare data'],
            HOTEL_SEARCH: ['🏨', location ? 'Hotels in ' + location : 'Hotel options', 'Accommodation recommendations'],
            BUDGET: ['💰', 'Travel budget', 'Estimated cost for your request'],
            RESEARCH: ['🔎', location ? 'Things to do in ' + location : 'Travel research', 'Destination recommendations and current research'],
            HISTORY: ['🕘', 'Recent saved trip', 'Retrieved from persistent trip history']
        }[type] || ['✦', location ? 'Travel information for ' + location : 'Travel information', 'Knowledge-backed answer to your question'];

        const isMulti = type === 'MULTI_CAPABILITY' || type === 'MULTI_INTENT';
        const widgets = [];
        if (isMulti || type === 'WEATHER') { if (hasWeather) widgets.push({key:'weather', icon:'☀️', title:'Weather', subtitle:'Current conditions and forecast', body:buildWeatherSection(weather, {focused: !isMulti})}); }
        if (isMulti || type === 'FLIGHT_SEARCH') { if (flights.length) widgets.push({key:'flights', icon:'✈️', title:'Flights', subtitle:'Available schedules and fare data', body:buildFlightsSection(flights)}); }
        if (isMulti || type === 'HOTEL_SEARCH') { if (hotels.length) widgets.push({key:'hotels', icon:'🏨', title:'Hotels', subtitle:'Accommodation recommendations', body:buildHotelsSection(hotels)}); }
        if (isMulti || type === 'BUDGET') { if (hasBudget) widgets.push({key:'budget', icon:'💰', title:'Budget', subtitle:'Estimated cost', body:buildBudgetTable(budget)}); }
        if ((type === 'RESEARCH' || type === 'HISTORY' || (!isMulti && !widgets.length)) && hasAnswer) widgets.push({key:'answer', icon:meta[0], title:meta[1], subtitle:meta[2], body:'<div class="specialist-answer">' + formatKnowledgeText(answer) + '</div>'});

        const nav = widgets.length > 1 ? '<nav class="plan-section-nav">' + widgets.map((w,i) => '<button type="button" class="plan-nav-btn' + (i === 0 ? ' active' : '') + '" data-section-target="' + w.key + '" title="Open ' + escapeHtml(w.title) + '" aria-label="Open ' + escapeHtml(w.title) + '">' + w.icon + ' ' + escapeHtml(w.title) + '</button>').join('') + '</nav>' : '';
        let steps = '';
        widgets.forEach((w, i) => {
            steps += '<section id="section-' + w.key + '" class="plan-step" data-widget-title="' + escapeHtml(w.title) + '">'
                + '<div class="plan-step-rail"><span class="plan-step-number" title="Step ' + (i + 1) + '" aria-hidden="true">' + (i + 1) + '</span><span class="plan-step-line"></span></div>'
                + '<div class="plan-step-card"><div class="plan-step-head"><div class="plan-step-heading"><div class="plan-step-icon">' + w.icon + '</div><div><h3>' + escapeHtml(w.title) + ' <span class="plan-check">✓</span></h3><p>' + escapeHtml(w.subtitle) + '</p></div></div><div class="widget-head-actions"><span class="ready-badge">✓ Complete</span>' + widgetControls(w.key, w.title) + '</div></div>'
                + w.body + '</div></section>';
        });
        if (!steps) steps = '<div class="specialist-empty">No additional structured details were returned for this request.</div>';

        let html = '<div class="plan-workspace specialist-dynamic-workspace">'
            + '<header class="plan-header specialist-dynamic-header"><div class="plan-header-top"><div><div class="trip-eyebrow">AGENTICTRIPAI · ' + escapeHtml(type.replace(/_/g, ' ')) + '</div><h1>' + escapeHtml(meta[1]) + '</h1></div><div class="plan-header-status">' + (needsUserInput ? 'Needs your input' : '✓ Complete') + '</div></div>'
            + '<div class="plan-header-facts"><span>✦ Requested information</span>' + (location ? '<span>📍 ' + escapeHtml(location) + '</span>' : '') + (userRequest ? '<span class="specialist-request">' + escapeHtml(String(userRequest).slice(0, 100)) + '</span>' : '') + '</div></header>'
            + (needsUserInput && clarification ? '<div class="agent-clarification-banner"><strong>✦ Action needed</strong><span>' + escapeHtml(clarification) + '</span></div>' : '')
            + nav + '<div class="plan-steps">' + steps + '</div>'
            + '<div class="widget-restore-tray" data-widget-restore-tray style="display:none"><span class="widget-restore-label">Closed widgets</span></div>'
            + '</div>';
        return html;
    };

    const formatPlanTimestamp = (value) => {
        if (!value) return '';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        return date.toLocaleString('en-IN', {
            day: '2-digit', month: 'short', year: 'numeric',
            hour: '2-digit', minute: '2-digit'
        });
    };

    const buildPlanCardHtml = (data, userRequest) => {
        const plan = data.plan || {};
        const trip = plan.trip || {};
        const tips = safeTripTips(plan.tips || data.finalPlan || '');
        if (data.status === 'ERROR' || (tips && String(tips).startsWith('Error:'))) {
            return '<div class="result-card"><div class="result-hero"><div class="result-eyebrow">Agent response</div><h2 class="result-title">Something went wrong</h2></div><div class="result-body"><div class="validation-banner">⚠ ' + escapeHtml(safeUiErrorMessage()) + '</div></div></div>';
        }

        const hasItineraryData = !!(plan.itinerary && Array.isArray(plan.itinerary.days) && plan.itinerary.days.length);
        const requestType = String(data.requestType || trip.requestType || '').toUpperCase();
        const isTripPlan = data.tripPlanning === true || requestType === 'TRIP_PLANNING';
        const flights = Array.isArray(plan.flights) ? plan.flights.filter(Boolean) : [];
        const hotels = Array.isArray(plan.hotels) ? plan.hotels.filter(Boolean) : [];
        const hasFlights = flights.length > 0;
        const hasReturnFlights = hasFlights && flights.some(f => String(f.status || '').toLowerCase() !== 'unavailable' && String(f.direction || 'outbound').toLowerCase() === 'return');
        const roundTripReturnMissing = hasFlights && trip.roundTrip !== false && !hasReturnFlights;
        const hasHotels = hotels.length > 0;
        const hasItinerary = hasItineraryData;
        const hasBudget = !!(plan.budget && Array.isArray(plan.budget.lineItems) && plan.budget.lineItems.length);
        const hasWeather = !!(plan.weather && ((plan.weather.current && plan.weather.current.temperature != null) || (Array.isArray(plan.weather.days) && plan.weather.days.some(d => d && (d.high != null || d.low != null || d.condition))) || (typeof plan.weather.summary === 'string' && /no weather details found/i.test(plan.weather.summary))));
        const knowledge = plan.knowledge || {};
        const exec = data.execution || {};
        const hasKnowledge = !!(knowledge.available === true || knowledge.answer || exec.ragAnswer || data.ragAnswer);
        const quality = trip.qualityScore != null ? trip.qualityScore : (plan.validation && plan.validation.quality && plan.validation.quality.overall > 0 ? Math.round(plan.validation.quality.overall * 100) : null);
        const responseStatus = String(data.status || '').toUpperCase();
        const tripStatus = String(trip.status || '').toUpperCase();
        const goalStatus = String(data.goalStatus || '').toUpperCase();
        const awaitingApproval = Boolean(data.awaitingApproval === true || trip.awaitingApproval === true || responseStatus === 'PENDING_APPROVAL' || tripStatus === 'PENDING_APPROVAL');
        const approvalState = String(data.approvalState || trip.approvalState || '').toUpperCase();
        const retryableTasks = Array.isArray(data.retryableTasks) ? data.retryableTasks.filter(Boolean) : [];
        const unmetCriteria = Array.isArray(data.unmetCriteria) ? data.unmetCriteria.filter(Boolean) : [];
        const blockingIssues = Array.isArray(data.blockingIssues) ? data.blockingIssues.filter(Boolean) : [];
        const complete = goalStatus === 'ACHIEVED' && !awaitingApproval && (!isTripPlan || approvalState === 'APPROVED');
        const flightDateUnconfirmed = hasFlights && flights.some(f => /not independently confirmed/i.test(String(f.notes || '')));
        const origin = trip.origin || data.origin || '';
        const destination = trip.destination || data.destination || '';
        const route = origin && destination ? origin + ' → ' + destination : (destination || 'Travel plan');
        const nights = trip.nights || '';
        const travelers = data.travelers || trip.travelers || 1;
        const budgetLabel = trip.budgetLabel || data.budgetLabel || '';
        const requirements = Array.isArray(trip.requirements) ? trip.requirements : [];
        const imageUrl = trip.imageUrl || trip.heroImageUrl || data.destinationImageUrl || '';
        const statusText = approvalState === 'APPROVED' && goalStatus === 'ACHIEVED'
            ? 'Plan Approved'
            : approvalState === 'REJECTED'
                ? 'Plan Rejected'
                : goalStatus === 'PARTIAL' || goalStatus === 'FAILED'
                    ? 'Action Required'
                    : complete
                        ? 'Plan Ready'
                        : (awaitingApproval || (isTripPlan && approvalState !== 'APPROVED'))
                            ? 'Ready for review'
                            : 'Planning in progress';
        const widgetBadge = (badge) => {
            if (approvalState === 'APPROVED') return '✓ Approved';
            if (approvalState === 'REJECTED') return '✕ Rejected';
            return badge;
        };

        // Non-trip requests stay intentionally lightweight: only requested/returned widgets are shown.
        if (!isTripPlan) {
            return buildSpecialistResponseHtml(data, userRequest);
        }

        const section = (number, key, icon, title, subtitle, body, badge, extraClass) => {
            if (!body) return '';
            return '<section id="section-' + key + '" class="plan-step ' + (extraClass || '') + '" data-widget-title="' + escapeHtml(title) + '">'
                + '<div class="plan-step-rail"><span class="plan-step-number" title="Step ' + number + '" aria-hidden="true">' + number + '</span><span class="plan-step-line"></span></div>'
                + '<div class="plan-step-card">'
                + '<div class="plan-step-head"><div class="plan-step-heading"><div class="plan-step-icon">' + icon + '</div><div><h3>' + title + ' <span class="plan-check">✓</span></h3><p>' + subtitle + '</p></div></div>'
                + '<div class="widget-head-actions">'
                + (badge ? '<span class="ready-badge ' + (badge === 'Review' ? 'review-badge' : badge === '✓ Approved' ? 'approved-badge' : badge === '✕ Rejected' ? 'rejected-badge' : '') + '">' + badge + '</span>' : '')
                + widgetControls(key, title)
                + '</div></div>' + body + '</div></section>';
        };

        let html = '<div class="plan-workspace">';
        html += '<header class="plan-header">'
            + '<div class="plan-header-top"><div><div class="trip-eyebrow">AI TRAVEL PLAN</div><h1>' + escapeHtml(route) + '</h1></div><div class="plan-header-status' + ((goalStatus === 'PARTIAL' || goalStatus === 'FAILED') ? ' partial-status' : '') + '">' + escapeHtml(statusText) + '</div></div>'
            + '<div class="plan-header-facts">'
            + '<span>📅 ' + escapeHtml(trip.datesFlexible === true || (!trip.departureDate && !trip.returnDate) ? 'Dates flexible' : formatDateRange(trip.departureDate, trip.returnDate)) + (nights ? ' · ' + escapeHtml(String(nights)) + ' nights' : '') + '</span>'
            + '<span>👤 ' + escapeHtml(String(travelers)) + ' traveler' + (Number(travelers) === 1 ? '' : 's') + '</span>'
            + '<span>↔ ' + (trip.roundTrip === false ? 'One-way' : 'Round trip') + '</span>'
            + (budgetLabel ? '<span>💰 ' + escapeHtml(budgetLabel) + '</span>' : '')
            + (trip.generatedAt ? '<span>🕐 Generated ' + escapeHtml(formatPlanTimestamp(trip.generatedAt)) + '</span>' : '')
            + (trip.updatedAt && trip.updatedAt !== trip.generatedAt ? '<span>↻ Updated ' + escapeHtml(formatPlanTimestamp(trip.updatedAt)) + '</span>' : '')
            + '</div>'
            + (requirements.length ? '<div class="plan-requirements">' + requirements.map(r => '<span>✓ ' + escapeHtml(r) + '</span>').join('') + '</div>' : '')
            + '</header>'
            ;
        if (goalStatus === 'PARTIAL' || goalStatus === 'FAILED') {
            const failedLabels = retryableTasks.map(task => humanTaskLabel(task));
            const failedSummary = failedLabels.length ? failedLabels.join(', ') : 'failed capabilities';
            const topRetry = (awaitingApproval && data.threadId && retryableTasks.length)
                ? '<div class="recovery-actions"><button type="button" class="recovery-retry" data-plan-action="retry" data-retry-task="ALL_FAILED" data-thread-id="' + escapeHtml(data.threadId) + '">↻ Retry Failed Tasks</button></div>'
                : '';
            html += '<div class="recovery-action"><div class="recovery-title">⚠ Your complete trip goal is not achieved yet</div><p class="recovery-copy">The successful parts are preserved. Retry the failed capability below. Dependent details will be rebuilt automatically after recovery succeeds.</p>'
                + (failedLabels.length ? '<div class="recovery-failed"><span>Failed:</span> ' + escapeHtml(failedSummary) + '</div>' : '')
                + ((unmetCriteria.length || blockingIssues.length) ? '<ul class="recovery-issues">' + [...unmetCriteria, ...blockingIssues].slice(0,6).map(x => '<li>' + escapeHtml(String(x)) + '</li>').join('') + '</ul>' : '')
                + topRetry + '</div>';
        }
        if (responseStatus === 'NEEDS_USER_INPUT' && String(data.clarificationRequired || '').trim()) {
            html += '<div class="agent-clarification-banner"><strong>✦ Action needed</strong><span>' + escapeHtml(String(data.clarificationRequired)) + '</span></div>';
        }

        const nav = [];
        if (hasFlights) nav.push(['flights','✈ Flights']);
        if (hasHotels) nav.push(['hotels','🏨 Hotels']);
        if (hasItinerary) nav.push(['itinerary','🗓 Itinerary']);
        if (hasWeather) nav.push(['weather','☀ Weather']);
        if (hasBudget) nav.push(['budget','💰 Budget']);
        if (hasKnowledge) nav.push(['knowledge','🧠 Tips']);
        if (nav.length > 1) html += '<nav class="plan-section-nav">' + nav.map((x,i) => '<button type="button" class="plan-nav-btn' + (i === 0 ? ' active' : '') + '" data-section-target="' + x[0] + '" title="Open ' + escapeHtml(x[1].replace(/^[^A-Za-z]+/, '')) + '" aria-label="Open ' + escapeHtml(x[1].replace(/^[^A-Za-z]+/, '')) + '">' + x[1] + '</button>').join('') + '</nav>';

        html += '<div class="plan-steps">';
        let step = 1;
        if (hasFlights) {
            const badge = (flightDateUnconfirmed || roundTripReturnMissing) ? 'Review' : '✓ Ready';
            html += section(step++, 'flights', '✈️', 'Flights', flightDateUnconfirmed ? 'Live schedules · requested date not independently confirmed' : 'Best available flight options for your trip', buildFlightsSection(flights), widgetBadge(badge));
        }
        if (hasHotels) html += section(step++, 'hotels', '🏨', 'Hotels', 'Top accommodation recommendations', buildHotelsSection(hotels), widgetBadge('✓ Ready'));
        if (hasItinerary) html += section(step++, 'itinerary', '🗓️', 'Day-wise Itinerary', 'A complete day-by-day plan', buildItinerarySection(plan.itinerary), widgetBadge('✓ Ready'), 'plan-step-itinerary');
        if (hasWeather) html += section(step++, 'weather', '☀️', 'Weather & Best Time', 'Travel-date forecast and planning guidance', buildWeatherSection(plan.weather), widgetBadge('✓ Ready'));
        if (hasBudget) html += section(step++, 'budget', '💰', 'Budget Breakdown', 'Estimated cost for your trip', buildBudgetTable(plan.budget), widgetBadge('✓ Ready'));
        if (hasKnowledge) {
            const knowledgeBody = buildKnowledgeGuidanceSection(data, plan);
            if (knowledgeBody) html += section(step++, 'knowledge', '🧠', 'Travel Knowledge & Tips', 'Useful destination guidance for your trip', knowledgeBody.replace(/^<section[^>]*>|<\/section>$/g, ''), widgetBadge('✓ Grounded'));
        }
        if (tips && !hasKnowledge) html += section(step++, 'tips', '💡', 'Travel Tips', 'Practical trip-specific suggestions', '<div class="knowledge-answer">' + formatKnowledgeText(tips) + '</div>', widgetBadge('✓ Ready'));
        html += '<div class="widget-restore-tray" data-widget-restore-tray style="display:none"><span class="widget-restore-label">Closed widgets</span></div>';
        html += '</div>';

        if (data.validationErrors?.length || data.semanticNotes?.length || data.ragJudge) {
            html += '<div class="plan-validation">' + buildPlanReview(data, userRequest) + '</div>';
        }

        if (awaitingApproval && data.threadId && data.tripPlanning !== false && (goalStatus === 'PARTIAL' || goalStatus === 'FAILED')) {
            const failedLabels = retryableTasks.map(task => humanTaskLabel(task));
            const failedSummary = failedLabels.length
                ? failedLabels.join(', ')
                : 'failed tasks';
            const retryButton = retryableTasks.length
                ? '<button type="button" class="retry-failed" data-plan-action="retry" data-retry-task="ALL_FAILED" data-thread-id="' + escapeHtml(data.threadId) + '">↻ Retry Failed Tasks</button>'
                : '';
            html += '<section class="plan-final-action"><div><span class="decision-status pending"><i class="decision-dot"></i> Action required</span><h3>Retry the failed tasks</h3><p>The goal is not complete yet. Failed tasks: <strong>' + escapeHtml(failedSummary) + '</strong>. Successful work will be preserved, and dependent tasks will run again only when their prerequisites succeed.</p></div><div class="final-actions-inline">' + retryButton + '</div></section>';
        } else if (awaitingApproval && data.threadId && data.tripPlanning !== false) {
            html += '<section class="plan-final-action"><div><span class="decision-status pending"><i class="decision-dot"></i> Waiting for your decision</span><h3>Ready to finalize?</h3><p>The goal is achieved. Review the complete plan, then approve it, request a change, or reject it.</p></div><div class="final-actions-inline"><button type="button" class="final-approve" data-plan-action="approve" data-thread-id="' + escapeHtml(data.threadId) + '">✓ Approve</button><button type="button" class="final-modify" data-plan-action="modify" data-thread-id="' + escapeHtml(data.threadId) + '">✎ Modify</button><button type="button" class="final-reject" data-plan-action="reject" data-thread-id="' + escapeHtml(data.threadId) + '">✕ Reject</button></div></section>';
        } else if (approvalState === 'APPROVED' && goalStatus === 'ACHIEVED') {
            html += '<section class="plan-final-action confirmed"><div><span class="decision-status"><i class="decision-dot"></i> Plan approved</span><h3>✓ Trip plan approved</h3><p>This plan has been approved and finalized.</p></div></section>';
        } else if (approvalState === 'REJECTED') {
            html += '<section class="plan-final-action rejected"><div><span class="decision-status"><i class="decision-dot"></i> Plan rejected</span><h3>✕ Trip plan rejected</h3><p>This plan was rejected and is no longer awaiting a decision.</p></div></section>';
        }

        html += '</div>';
        return html;
    };

    const findUserRequestForPlan = (beforeNode) => {
        const users = chatThread.querySelectorAll('.chat-msg.user .chat-body');
        if (!users.length) {
            return '';
        }
        if (!beforeNode) {
            return users[users.length - 1].textContent || '';
        }
        let last = '';
        for (const row of chatThread.querySelectorAll('.chat-msg')) {
            if (row === beforeNode) {
                break;
            }
            if (row.classList.contains('user')) {
                last = row.querySelector('.chat-body')?.textContent || '';
            }
        }
        return last;
    };

    const renderUserMessage = (text, persist) => {
        markChatActive();
        const row = document.createElement('div');
        row.className = 'chat-msg user';
        row.innerHTML =
            '<div class="chat-avatar">You</div>' +
            '<div class="chat-bubble"><div class="chat-role">You</div><div class="chat-body"></div></div>';
        row.querySelector('.chat-body').textContent = text;
        chatThread.appendChild(row);
        scrollChat();
        if (persist) {
            ensureCurrentChat(text);
            persistMessage('user', text, null);
        }
        return row;
    };

    const updateWidgetFoldState = (section) => {
        if (!section) return;
        const title = section.dataset.widgetTitle || section.id.replace(/^section-/, '');
        const collapsed = section.classList.contains('widget-collapsed');
        const collapseControl = section.querySelector('[data-widget-action="collapse"]');
        if (collapseControl) {
            collapseControl.textContent = collapsed ? '⌄' : '⌃';
            collapseControl.title = collapsed ? 'Fold out ' + title : 'Fold in ' + title;
            collapseControl.setAttribute('aria-label', collapseControl.title);
            collapseControl.setAttribute('aria-expanded', String(!collapsed));
            collapseControl.dataset.foldState = collapsed ? 'collapsed' : 'expanded';
        }
        const expandControl = section.querySelector('[data-widget-action="expand"]');
        if (expandControl) {
            expandControl.textContent = section.classList.contains('widget-expanded') ? '↙' : '⛶';
            expandControl.title = section.classList.contains('widget-expanded') ? 'Restore ' + title : 'Expand ' + title;
            expandControl.setAttribute('aria-label', expandControl.title);
        }
        const workspace = section.closest('.plan-workspace');
        const navButton = workspace?.querySelector('[data-section-target="' + CSS.escape(section.id.replace(/^section-/, '')) + '"]');
        if (navButton) {
            navButton.classList.toggle('is-folded', collapsed);
            navButton.title = collapsed ? 'Folded — click to open ' + title : 'Open ' + title;
            navButton.setAttribute('aria-expanded', String(!collapsed));
        }
    };

    const renderAssistantBody = (body, data, userRequestHint) => {
        const userRequest = userRequestHint || '';
        const responseType = String(data?.requestType || data?.plan?.trip?.requestType || '').toUpperCase();
        const specialistResponse = data && data.tripPlanning !== true
            && ['WEATHER','FLIGHT_SEARCH','HOTEL_SEARCH','BUDGET','RESEARCH','MULTI_CAPABILITY','MULTI_INTENT','TRAVEL_INFORMATION','HISTORY','GENERAL'].includes(responseType);
        const hasStructuredResponse = !!(data && (
            data.threadId || data.plan || data.requestType || data.weather ||
            data.flights || data.hotels || data.budget || data.execution ||
            data.ragAnswer || data.knowledge
        ));
        if (responseType === 'HISTORY') {
            body.innerHTML = buildHistoryResponseHtml(data);
        } else if (specialistResponse) {
            body.innerHTML = buildSpecialistResponseHtml(data, userRequest);
        } else if (hasStructuredResponse) {
            body.innerHTML = buildPlanCardHtml(data, userRequest);
        } else {
            const text = data && (data.finalPlan || data.text) ? (data.finalPlan || data.text) : 'Plan response available.';
            body.innerHTML = '<div class="server-memory-card">' + formatKnowledgeText(text) + '</div>';
        }
        body.querySelectorAll('.plan-step').forEach(section => updateWidgetFoldState(section));
        body.querySelectorAll('[data-section-target]').forEach(tab => {
            tab.addEventListener('click', () => {
                body.querySelectorAll('.workspace-tab,.plan-nav-btn').forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                const target = tab.dataset.sectionTarget;
                if (target === 'overview') {
                    body.querySelector('.trip-banner')?.scrollIntoView({behavior:'smooth', block:'start'});
                    return;
                }
                const section = body.querySelector('#section-' + CSS.escape(target));
                if (!section) return;
                if (section.classList.contains('widget-collapsed')) {
                    section.classList.remove('widget-collapsed');
                    updateWidgetFoldState(section);
                }
                section.scrollIntoView({behavior:'smooth', block:'center'});
            });
        });
    };

    const renderAssistantMessage = (data, persist, userRequestHint) => {
        markChatActive();
        const row = document.createElement('div');
        row.className = 'chat-msg assistant';
        if (data?.threadId) row.dataset.threadId = String(data.threadId);
        row.innerHTML =
            '<div class="chat-avatar">AI</div>' +
            '<div class="chat-bubble">' +
            '<div class="chat-role">AgenticTripAI</div>' +
            '<div class="chat-body"></div>' +
            '<div class="chat-actions"></div>' +
            '</div>';
        const userRequest = userRequestHint || findUserRequestForPlan(row);
        renderAssistantBody(row.querySelector('.chat-body'), data, userRequest);
        chatThread.appendChild(row);
        scrollChat();
        if (persist) {
            ensureCurrentChat(data.destination || data.routeSummary || 'Trip plan');
            persistMessage('assistant', formatPlanSummary(data), data);
        }
        return row;
    };

    const updatePersistedPlanForThread = (threadId, data) => {
        try {
            const store = loadStore();
            const chat = store.chats.find(c => c.id === currentChatId);
            if (!chat || !Array.isArray(chat.messages)) return;
            for (let i = chat.messages.length - 1; i >= 0; i--) {
                const message = chat.messages[i];
                if (message?.role === 'assistant' && String(message?.planData?.threadId || '') === String(threadId)) {
                    message.planData = data;
                    message.text = formatPlanSummary(data);
                    chat.updatedAt = Date.now();
                    saveStore(store);
                    renderHistoryList();
                    return;
                }
            }
        } catch (e) {
            console.warn('Could not update persisted plan decision state', e);
        }
    };

    const replaceDecisionPlanInPlace = (sourceRow, data) => {
        if (!sourceRow) return false;
        const body = sourceRow.querySelector('.chat-body');
        if (!body) return false;
        if (data?.threadId) sourceRow.dataset.threadId = String(data.threadId);
        renderAssistantBody(body, data, findUserRequestForPlan(sourceRow));
        updatePersistedPlanForThread(data?.threadId, data);
        scrollChat();
        return true;
    };

    const appendUserMessage = (text) => renderUserMessage(text, true);
    const appendAssistantMessage = (data) => renderAssistantMessage(data, true);

    const normalizeSteps = (steps) => (steps || []).filter(step => step && step.node);

    const dedupeSteps = (steps) => {
        const seen = new Set();
        return normalizeSteps(steps).filter(step => {
            const key = (step.attempt || 1) + '|' + step.node + '|' + (step.detail || '');
            if (seen.has(key)) {
                return false;
            }
            seen.add(key);
            return true;
        });
    };

    const formatStepLine = (step) => {
        const status = (step.status || '').toUpperCase();
        const icon = status === 'PARTIAL' || status === 'WARN' ? '⚠ '
            : status === 'SKIPPED' || status === 'SKIP' ? '○ '
            : status === 'RETRYING' ? '↻ '
            : status === 'FAILED' ? '✕ '
            : status === 'WAITING_HUMAN' ? '⏸ '
            : '✓ ';
        return icon + step.node
            + (step.durationMs ? ' (' + (step.durationMs / 1000).toFixed(1) + 's)' : '')
            + (step.model ? ' [' + step.model + ']' : '')
            + (step.toolCalls ? ' tools=' + step.toolCalls : '')
            + (step.inputTokens ? ' tok=' + step.inputTokens + '/' + (step.outputTokens || 0) : '')
            + (step.detail ? ' — ' + step.detail : '');
    };

    const formatPlanSummary = (data) => {
        const title = tripTitle(data || {});
        const route = data && data.origin && data.destination
            ? data.origin + ' → ' + data.destination
            : (data && data.routeSummary) || title;
        const quality = data && data.planQuality && data.planQuality.overall > 0
            ? ' · Confidence ' + Math.round(data.planQuality.overall * 100) + '/100' : '';
        return title + ' — ' + route + quality;
    };

    const formatPlan = (data) => formatPlanSummary(data);


    const LIVE_PHASES = [
        { id:'intent', label:'Understand' },
        { id:'plan', label:'Plan' },
        { id:'execute', label:'Execute' },
        { id:'evaluate', label:'Evaluate' },
        { id:'replan', label:'Replan' }
    ];

    const phaseForNode = (node) => {
        const n = String(node || '').toLowerCase();
        if (n === 'intent') return 'intent';
        if (n === 'plan') return 'plan';
        if (n === 'execute') return 'execute';
        if (n === 'evaluate') return 'evaluate';
        if (n === 'replan') return 'replan';
        if (n === 'final' || n === 'complete' || n === 'hitl') return 'final';
        return null;
    };

    const humanTaskLabel = (id) => ({
        flights:'Flights', hotels:'Hotels', research:'Attractions', weather:'Weather',
        knowledge:'Travel knowledge', history:'Travel history', budget:'Budget', itinerary:'Itinerary'
    }[String(id || '').toLowerCase()] || String(id || '').replace(/[-_]+/g,' ').replace(/\b\w/g,c=>c.toUpperCase()));

    const createLiveResponse = () => {
        const row = document.createElement('div');
        row.className = 'live-response';
        const phases = LIVE_PHASES.map(p =>
            '<div class="live-phase" data-phase="' + p.id + '">' +
              '<div class="live-phase-label"><i class="live-phase-dot"></i><span>' + p.label + '</span></div>' +
              '<div class="live-phase-detail">Waiting</div>' +
            '</div>'
        ).join('');
        row.innerHTML = '<div class="chat-avatar">AI</div><div class="live-response-card">'
            + '<div class="live-head"><div class="live-title">AgenticTripAI <span class="live-dots"><i></i><i></i><i></i></span></div><div class="live-head-actions"><div class="live-state">Working</div><button type="button" class="live-stop-button" data-stop-run="true">■ Stop</button></div></div>'
            + '<div class="live-timeline">' + phases + '</div>'
            + '<div class="live-activity"><i class="live-activity-dot"></i><span class="live-activity-text">Starting…</span></div>'
            + '<div class="live-tasks"><div class="live-tasks-head"><span>Live execution</span><span class="live-task-count"></span></div><div class="live-task-list"></div></div>'
            + '</div>';
        chatThread.appendChild(row);
        scrollChat();
        return {
            row,
            state: row.querySelector('.live-state'),
            stopButton: row.querySelector('[data-stop-run]'),
            activity: row.querySelector('.live-activity-text'),
            phases: row.querySelector('.live-timeline'),
            tasks: row.querySelector('.live-tasks'),
            taskList: row.querySelector('.live-task-list'),
            taskCount: row.querySelector('.live-task-count'),
            taskMap: new Map()
        };
    };

    const setLivePhase = (live, id, status, detail) => {
        if (!live || !id || id === 'final') return;
        const el = live.phases.querySelector('[data-phase="' + id + '"]');
        if (!el) return;
        el.classList.remove('active','done','failed');
        if (status === 'RUNNING') el.classList.add('active');
        if (status === 'SUCCEEDED') el.classList.add('done');
        if (status === 'FAILED') el.classList.add('failed');
        const d = el.querySelector('.live-phase-detail');
        if (d) d.textContent = detail || (status === 'RUNNING' ? 'In progress' : status === 'SUCCEEDED' ? 'Complete' : 'Failed');
    };

    const setLiveTask = (live, id, status, message) => {
        if (!live || !id) return;
        const key = String(id).toLowerCase();
        let item = live.taskMap.get(key);
        if (!item) {
            item = document.createElement('span');
            item.className = 'live-task';
            item.innerHTML = '<i class="task-dot"></i><span class="task-name"></span><span class="task-status"></span>';
            live.taskList.appendChild(item);
            live.taskMap.set(key, item);
        }
        item.classList.remove('running','done','failed');
        item.classList.add(status === 'RUNNING' ? 'running' : status === 'FAILED' ? 'failed' : 'done');
        item.querySelector('.task-name').textContent = humanTaskLabel(id);
        item.querySelector('.task-status').textContent = status === 'RUNNING' ? 'running' : status === 'FAILED' ? 'failed' : 'complete';
        live.tasks.classList.add('visible');
        const running = [...live.taskMap.entries()].filter(([,x]) => x.classList.contains('running'));
        const done = [...live.taskMap.values()].filter(x => x.classList.contains('done')).length;
        live.taskCount.textContent = running.length ? (running.length + ' active') : (done + ' completed');
        if (live.activity) {
            if (status === 'RUNNING') {
                live.activity.textContent = (message || humanTaskLabel(id)) + '…';
            } else if (status === 'FAILED') {
                live.activity.textContent = (message || humanTaskLabel(id)) + ' failed';
            } else if (running.length) {
                live.activity.textContent = (running[0][1].querySelector('span')?.textContent || 'Working') + '…';
            } else {
                live.activity.textContent = (message || humanTaskLabel(id)) + ' complete';
            }
        }
    };

    const updateLiveResponse = (live, node, phase = 'start', message) => {
        if (!live || !node) return;
        const raw = String(node).replace(/^__+|__+$/g, '').replace(/[-_]+/g, ' ').trim();
        const id = String(node).toLowerCase().replace(/\s+/g,'_');
        const phaseId = phaseForNode(id);
        if (!phaseId) return;
        const status = phase === 'start' ? 'RUNNING' : 'SUCCEEDED';

        // Phase cards communicate workflow position only. They deliberately do
        // not repeat the detailed live activity message.
        setLivePhase(live, phaseId, status);

        // Keep the header intentionally stable: the exact current operation is
        // shown in ONE place only, in .live-activity.
        if (phase === 'start' && live.activity) {
            live.activity.textContent = message || ('Running ' + raw) + '…';
        } else if (phase === 'complete' && live.activity && !live.taskMap?.size) {
            live.activity.textContent = message || (raw + ' complete');
        }
    };

    const requestStopForLive = async (live, threadId) => {
        if (!live || !threadId) return false;
        const button = live.stopButton;
        if (button) {
            button.disabled = true;
            button.textContent = 'Stopping…';
            button.dataset.stopPending = 'false';
        }
        try {
            const res = await apiFetch('/api/plan/' + encodeURIComponent(threadId) + '/stop', { method: 'POST' });
            const payload = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(payload.error || safeUiErrorMessage());
            if (payload.status === 'NOT_RUNNING') {
                if (live.state) live.state.textContent = 'Already stopped';
                if (live.activity) live.activity.textContent = payload.message || 'This run is no longer active.';
                if (button) { button.disabled = true; button.textContent = 'Stopped'; }
                return false;
            }
            if (live.state) live.state.textContent = 'Stopping';
            if (live.activity) live.activity.textContent = payload.message || 'Saving your current checkpoint…';
            return true;
        } catch (error) {
            if (button) {
                button.disabled = false;
                button.textContent = '■ Stop';
            }
            if (live.state) live.state.textContent = 'Working';
            showToast(error?.message || safeUiErrorMessage());
            return false;
        }
    };

    const removeLiveResponse = (live) => {
        if (live && live.row && live.row.isConnected) live.row.remove();
    };

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        syncUserIdField();

        const text = promptInput.value.trim();
        if (!text) {
            return;
        }

        // Modify mode deliberately resumes the existing LangGraph checkpoint.
        // It must not create a new trip or rerun the original fan-out.
        if (modifyContext?.threadId) {
            const context = modifyContext;
            const success = await decide('/api/plan/modify', context.threadId, text, context.sourceRow);
            if (success) {
                exitModifyMode(true);
            }
            return;
        }

        const formData = new FormData(form);
        const payload = Object.fromEntries(formData.entries());
        payload.preferences = text;
        payload.prompt = text;

        appendUserMessage(text);
        promptInput.value = '';
        submitButton.disabled = true;
        loadingState.classList.add('visible');
        const liveResponse = createLiveResponse();

        try {
            const idempotencyKey = (window.crypto && crypto.randomUUID)
                ? crypto.randomUUID()
                : ('travel-' + Date.now() + '-' + Math.random().toString(36).slice(2));
            const startRes = await apiFetch('/api/plan/start', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Idempotency-Key': idempotencyKey
                },
                body: JSON.stringify(payload)
            });
            const startedBody = await startRes.text();
            if (!startRes.ok) {
                throw new Error(startedBody || 'Request failed');
            }
            const started = JSON.parse(startedBody);
            const threadId = started.threadId;
            liveResponse.row.dataset.threadId = threadId;
            const stopWasQueued = liveResponse.stopButton?.dataset.stopPending === 'true';
            if (liveResponse.stopButton) {
                liveResponse.stopButton.dataset.threadId = threadId;
                if (!stopWasQueued) {
                    liveResponse.stopButton.disabled = false;
                    liveResponse.stopButton.textContent = '■ Stop';
                }
            }
            const applyResumeCheckpoint = (data) => {
                if (!data) return;
                const rs = data.resumeState || data;
                const next = String(rs.nextNode || '').toLowerCase();
                const phaseRank = { intent: 0, plan: 1, execute: 2, evaluate: 3, replan: 4 };
                const nextRank = phaseRank[next] != null ? phaseRank[next] : 2;
                ['intent','plan','execute','evaluate','replan'].forEach((id, idx) => {
                    if (idx < nextRank) setLivePhase(liveResponse, id, 'SUCCEEDED', 'Complete');
                    else if (idx === nextRank) setLivePhase(liveResponse, id, 'RUNNING', 'Resuming');
                    else setLivePhase(liveResponse, id, 'PENDING', 'Waiting');
                });
                const statuses = rs.taskStatuses || {};
                Object.entries(statuses).forEach(([task, status]) => {
                    const normalized = String(status || '').toUpperCase();
                    if (normalized === 'SUCCEEDED') setLiveTask(liveResponse, task, 'SUCCEEDED', humanTaskLabel(task) + ' complete');
                    else if (normalized === 'RUNNING') setLiveTask(liveResponse, task, 'RUNNING', humanTaskLabel(task));
                    else if (normalized === 'FAILED') setLiveTask(liveResponse, task, 'FAILED', humanTaskLabel(task));
                });
                liveResponse.state.textContent = 'Working';
                liveResponse.activity.textContent = data.message || 'Resuming from your saved checkpoint…';
            };

            const isCheckpointContinuation = started.status === 'RESUMED' || started.status === 'MODIFYING';
            if (isCheckpointContinuation) {
                liveResponse.state.textContent = started.status === 'MODIFYING' ? 'Applying change' : 'Continuing';
                liveResponse.activity.textContent = started.status === 'MODIFYING'
                    ? 'Applying your change to the saved checkpoint…'
                    : 'Resuming from your saved checkpoint…';
                applyResumeCheckpoint(started);
            }
            const statusSpan = loadingState.querySelectorAll('span')[1];
            await new Promise((resolve, reject) => {
                let finished = false;
                const isResumedRun = started.status === 'RESUMED' || started.status === 'MODIFYING';
                const resumeCursor = isResumedRun && Number.isFinite(Number(started.resumeSinceEventId))
                    ? Number(started.resumeSinceEventId) : -1;
                const eventsUrl = resumeCursor >= 0
                    ? '/api/plan/' + encodeURIComponent(threadId) + '/events?afterId=' + encodeURIComponent(resumeCursor)
                    : '/api/plan/' + encodeURIComponent(threadId) + '/events';
                const es = new EventSource(eventsUrl);
                // A resumed thread contains the previous run's STOPPED event.
                // The durable cursor filters it in the normal case, but the UI
                // must also defend against an SSE replay/reconnect delivering an
                // older terminal event before the current RESUME marker.
                let resumeRunStarted = !isResumedRun;
                const isCurrentResumeEvent = (data) => {
                    if (!isResumedRun) return true;
                    const node = String(data?.node || '').toUpperCase();
                    const message = String(data?.message || '').toLowerCase();
                    if (node === 'RESUME' || node === 'MODIFY' || message.includes('continuing your saved request') || message.includes('applying your change')) {
                        resumeRunStarted = true;
                        return true;
                    }
                    return resumeRunStarted;
                };
                // If the user clicked Stop while /start was still allocating the
                // thread id, deliver that intent now that the target exists.
                if (stopWasQueued) {
                    void requestStopForLive(liveResponse, threadId);
                }
                // The backend emits a `started` event immediately after the
                // async graph begins.  Listen to it as well as `node`; without
                // this handler the live card stays on its initial
                // "Understanding request…" text until the first graph node
                // finishes, which can take a while when the intent model is
                // running.


                es.addEventListener('resume_state', (evt) => {
                    if (!isResumedRun) return;
                    try {
                        const data = JSON.parse(evt.data);
                        applyResumeCheckpoint(data);
                        resumeRunStarted = true;
                    } catch (ignored) {}
                });

                es.addEventListener('started', (evt) => {
                    // START only means the asynchronous graph was accepted.
                    // It is not an agent stage, so keep the neutral state until
                    // the first real node_start event arrives.
                    try {
                        const data = JSON.parse(evt.data);
                        if (!isCurrentResumeEvent(data)) return;
                        if (!data.node || String(data.node).toUpperCase() === 'START') {
                            liveResponse.state.textContent = 'Working';
                            if (liveResponse.activity) liveResponse.activity.textContent = 'Starting…';
                        }
                    } catch (ignored) {}
                });
                es.addEventListener('activity', (evt) => {
                    try {
                        const data = JSON.parse(evt.data);
                        if (!isCurrentResumeEvent(data)) return;
                        const msg = data.message || '';
                        if (!msg) return;
                        if (liveResponse.activity) liveResponse.activity.textContent = msg + '…';
                        const phaseId = phaseForNode(data.phase || '');
                        if (phaseId) setLivePhase(liveResponse, phaseId, 'RUNNING');
                    } catch (ignored) {}
                });
                es.addEventListener('node_start', (evt) => {
                    try {
                        const data = JSON.parse(evt.data);
                        if (!isCurrentResumeEvent(data)) return;
                        if (data.node) {
                            const msg = data.message || ('Running ' + data.node);
                            if (statusSpan) statusSpan.textContent = msg + '…';
                            updateLiveResponse(liveResponse, data.node, 'start', msg);
                        }
                    } catch (ignored) {}
                });
                es.addEventListener('node_complete', (evt) => {
                    try {
                        const data = JSON.parse(evt.data);
                        if (!isCurrentResumeEvent(data)) return;
                        if (data.node) {
                            const msg = data.message || data.node;
                            if (statusSpan) statusSpan.textContent = msg;
                            updateLiveResponse(liveResponse, data.node, 'complete', msg);
                        }
                    } catch (ignored) {}
                });
                es.addEventListener('task_start', (evt) => {
                    try {
                        const data = JSON.parse(evt.data);
                        if (!isCurrentResumeEvent(data)) return;
                        if (data.task) {
                            const msg = data.message || data.task;
                            if (statusSpan) statusSpan.textContent = msg + '…';
                            setLiveTask(liveResponse, data.task, 'RUNNING', msg);
                        }
                    } catch (ignored) {}
                });
                es.addEventListener('task_complete', (evt) => {
                    try {
                        const data = JSON.parse(evt.data);
                        if (!isCurrentResumeEvent(data)) return;
                        if (data.task) {
                            const msg = data.message || data.task;
                            const failed = data.status === 'FAILED';
                            if (statusSpan) statusSpan.textContent = failed ? msg + ' — failed' : msg;
                            setLiveTask(liveResponse, data.task, failed ? 'FAILED' : 'SUCCEEDED', msg);
                        }
                    } catch (ignored) {}
                });
                // `node` is a legacy compatibility event. Do not render it;
                // node_start/node_complete already provide the authoritative lifecycle.
                es.addEventListener('node', () => {});
                es.addEventListener('stop_requested', (evt) => {
                    try {
                        const data = JSON.parse(evt.data);
                        if (!isCurrentResumeEvent(data)) return;
                        liveResponse.state.textContent = 'Stopping';
                        liveResponse.activity.textContent = data.message || 'Saving your current checkpoint…';
                        if (liveResponse.stopButton) {
                            liveResponse.stopButton.disabled = true;
                            liveResponse.stopButton.textContent = 'Stopping…';
                        }
                    } catch (ignored) {}
                });
                es.addEventListener('stopped', (evt) => {
                    try {
                        const data = JSON.parse(evt.data);
                        if (!isCurrentResumeEvent(data)) return;
                    } catch (_) {
                        if (isResumedRun && !resumeRunStarted) return;
                    }
                    finished = true;
                    es.close();
                    liveResponse.state.textContent = 'Stopped';
                    liveResponse.activity.textContent = 'Checkpoint saved. Say Continue to resume this request.';
                    if (liveResponse.stopButton) {
                        liveResponse.stopButton.disabled = true;
                        liveResponse.stopButton.textContent = 'Stopped';
                    }
                    submitButton.disabled = false;
                    loadingState.classList.remove('visible');
                    updateComposerState();
                    resolve();
                });
                es.addEventListener('complete', async (evt) => {
                    if (isResumedRun && !resumeRunStarted) return;
                    finished = true;
                    es.close();
                    liveResponse.state.textContent = 'Plan ready';
                    if (liveResponse.stopButton) { liveResponse.stopButton.disabled = true; liveResponse.stopButton.textContent = 'Done'; }
                    try {
                        const wrapper = JSON.parse(evt.data);
                        const data = wrapper.plan || wrapper;
                        try {
                            const hist = await apiFetch('/api/plan/' + encodeURIComponent(threadId) + '/history').then(r => r.json());
                            if (!data.execution) {
                                data.execution = {};
                            }
                            data.execution.executionHistory = (hist.snapshots || []).map(s =>
                                (s.node || '?') + ' → ' + (s.next || 'end')
                                + (s.planQuality != null ? ' q=' + (s.planQuality * 100).toFixed(0) + '%' : '')).join('\n');
                            if (hist.planQuality) {
                                data.planQuality = hist.planQuality;
                            }
                            if (hist.nodeFailure) {
                                data.nodeFailure = hist.nodeFailure;
                            }
                            if (hist.semanticNotes) {
                                data.semanticNotes = hist.semanticNotes;
                            }
                            if (hist.executionTimeline && hist.executionTimeline.length) {
                                data.execution.timeline = hist.executionTimeline;
                            }
                        } catch (ignored) {}
                        liveResponse.row.classList.add('live-complete');
                        setTimeout(() => removeLiveResponse(liveResponse), 260);
                        appendAssistantMessage(data);
                        loadDbTrips(false);
                        resolve();
                    } catch (error) {
                        reject(error);
                    }
                });
                es.addEventListener('failed', (evt) => {
                    if (isResumedRun && !resumeRunStarted) return;
                    finished = true;
                    es.close();
                    liveResponse.state.textContent = 'Plan failed';
                    if (liveResponse.stopButton) { liveResponse.stopButton.disabled = true; liveResponse.stopButton.textContent = 'Done'; }
                    liveResponse.row.classList.add('live-complete');
                    setTimeout(() => removeLiveResponse(liveResponse), 260);
                    try {
                        const data = JSON.parse(evt.data);
                        reject(new Error(safeUiErrorMessage()));
                    } catch (error) {
                        reject(error);
                    }
                });
                es.onerror = () => {
                    if (finished) {
                        return;
                    }
                    es.close();
                    reject(new Error(safeUiErrorMessage()));
                };
            });
        } catch (error) {
            removeLiveResponse(liveResponse);
            appendAssistantMessage({
                status: 'ERROR',
                finalPlan: safeUiErrorMessage(),
                awaitingApproval: false,
                pipeline: []
            });
        } finally {
            submitButton.disabled = false;
            loadingState.classList.remove('visible');
            updateComposerState();
            promptInput.focus();
        }
    });

    const retryWithLiveProgress = async (threadId, task, sourceRow) => {
        if (!threadId) {
            showToast('This plan no longer has an active recovery thread. Please create a new plan.');
            return false;
        }

        const liveResponse = createLiveResponse();
        // Keep the live card directly after the plan being retried instead of
        // sending it to the bottom of a long conversation.
        if (sourceRow?.parentNode) {
            sourceRow.parentNode.insertBefore(liveResponse.row, sourceRow.nextSibling);
        }
        liveResponse.state.textContent = 'Retrying';
        liveResponse.activity.textContent = task === 'ALL_FAILED'
            ? 'Retrying failed tasks…'
            : 'Retrying ' + humanTaskLabel(task) + '…';
        liveResponse.row.dataset.threadId = threadId;
        if (liveResponse.stopButton) { liveResponse.stopButton.disabled = false; liveResponse.stopButton.dataset.threadId = threadId; }

        sourceRow?.querySelectorAll('[data-plan-action]').forEach(btn => {
            btn.disabled = true;
            btn.dataset.busy = 'true';
        });
        submitButton.disabled = true;
        loadingState.classList.add('visible');

        let finished = false;
        let es;
        let resolveStream;
        let rejectStream;
        const streamDone = new Promise((resolve, reject) => {
            resolveStream = resolve;
            rejectStream = reject;
        });

        const finishStream = (ok, error) => {
            if (finished) return;
            finished = true;
            try { es?.close(); } catch (_) {}
            if (ok) resolveStream();
            else rejectStream(error || new Error(safeUiErrorMessage()));
        };

        try {
            // IMPORTANT: subscribe before POST /retry. The existing thread has
            // an old terminal event, so liveOnly=true starts after that event.
            await new Promise((resolveOpen, rejectOpen) => {
                es = new EventSource('/api/plan/' + encodeURIComponent(threadId) + '/events?liveOnly=true');
                es.onopen = () => resolveOpen();
                es.onerror = () => {
                    if (!es || es.readyState === EventSource.CLOSED) {
                        rejectOpen(new Error(safeUiErrorMessage()));
                    }
                };
            });

            const statusSpan = loadingState.querySelectorAll('span')[1];
            es.addEventListener('started', (evt) => {
                try {
                    const data = JSON.parse(evt.data);
                    liveResponse.state.textContent = 'Working';
                    liveResponse.activity.textContent = data.message || 'Retrying failed tasks…';
                    if (statusSpan) statusSpan.textContent = (data.message || 'Retrying failed tasks') + '…';
                } catch (_) {}
            });
            es.addEventListener('activity', (evt) => {
                try {
                    const data = JSON.parse(evt.data);
                    if (data.message) liveResponse.activity.textContent = data.message + '…';
                    const phaseId = phaseForNode(data.phase || '');
                    if (phaseId) setLivePhase(liveResponse, phaseId, 'RUNNING');
                } catch (_) {}
            });
            es.addEventListener('node_start', (evt) => {
                try {
                    const data = JSON.parse(evt.data);
                    if (!data.node) return;
                    const msg = data.message || ('Running ' + data.node);
                    if (statusSpan) statusSpan.textContent = msg + '…';
                    updateLiveResponse(liveResponse, data.node, 'start', msg);
                } catch (_) {}
            });
            es.addEventListener('node_complete', (evt) => {
                try {
                    const data = JSON.parse(evt.data);
                    if (!data.node) return;
                    const failed = String(data.status || '').toUpperCase() === 'FAILED';
                    const msg = data.message || data.node;
                    if (statusSpan) statusSpan.textContent = failed ? msg + ' — failed' : msg;
                    updateLiveResponse(liveResponse, data.node, failed ? 'start' : 'complete', msg);
                    if (failed) setLivePhase(liveResponse, phaseForNode(data.node), 'FAILED', msg);
                } catch (_) {}
            });
            es.addEventListener('task_start', (evt) => {
                try {
                    const data = JSON.parse(evt.data);
                    if (data.task) setLiveTask(liveResponse, data.task, 'RUNNING', data.message || data.task);
                } catch (_) {}
            });
            es.addEventListener('task_complete', (evt) => {
                try {
                    const data = JSON.parse(evt.data);
                    if (data.task) {
                        const failed = String(data.status || '').toUpperCase() === 'FAILED';
                        setLiveTask(liveResponse, data.task, failed ? 'FAILED' : 'SUCCEEDED', data.message || data.task);
                    }
                } catch (_) {}
            });
            es.addEventListener('stop_requested', (evt) => {
                try {
                    const data = JSON.parse(evt.data);
                    liveResponse.state.textContent = 'Stopping';
                    liveResponse.activity.textContent = data.message || 'Saving your current checkpoint…';
                    if (liveResponse.stopButton) {
                        liveResponse.stopButton.disabled = true;
                        liveResponse.stopButton.textContent = 'Stopping…';
                    }
                } catch (_) {}
            });
            es.addEventListener('stopped', (evt) => {
                liveResponse.state.textContent = 'Stopped';
                liveResponse.activity.textContent = 'Checkpoint saved. Say Continue to resume this request.';
                if (liveResponse.stopButton) { liveResponse.stopButton.disabled = true; liveResponse.stopButton.textContent = 'Stopped'; }
                finishStream(true);
            });
            es.addEventListener('complete', async (evt) => {
                try {
                    const wrapper = JSON.parse(evt.data);
                    const data = wrapper.plan || wrapper;
                    liveResponse.state.textContent = 'Retry complete';
                    liveResponse.activity.textContent = 'Failed tasks processed';
                    replaceDecisionPlanInPlace(sourceRow, data);
                    updatePersistedPlanForThread(threadId, data);
                    loadDbTrips(false);
                    // Recovery is complete. The retry action is transient and
                    // should not remain as the latest chat message once the plan
                    // is ready for the user's decision.
                    if (retryUserRow?.isConnected) retryUserRow.remove();
                    finishStream(true);
                } catch (e) {
                    finishStream(false, e);
                }
            });
            es.addEventListener('failed', (evt) => {
                liveResponse.state.textContent = 'Retry failed';
                liveResponse.activity.textContent = 'Retry could not complete';
                finishStream(false, new Error(safeUiErrorMessage()));
            });

            // Now that SSE is definitely subscribed, start the actual retry.
            // This is a transient UI action, not a conversational user request.
            // Do not persist it into chat history; the retry progress card and the
            // updated plan already represent the recovery action.  Remove the
            // transient bubble after successful recovery so it cannot remain above
            // the final Approve / Modify / Reject state.
            const retryUserRow = renderUserMessage(
                task === 'ALL_FAILED' ? 'Retrying all failed tasks.' : 'Retrying ' + humanTaskLabel(task) + '.',
                false
            );
            const res = await apiFetch('/api/plan/retry', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId: currentUserId || userIdField.value.trim() || '',
                    threadId,
                    notes: task
                })
            });
            const payload = await res.json().catch(() => ({}));
            if (!res.ok) {
                throw new Error(payload.error || safeUiErrorMessage());
            }
            // The HTTP response is authoritative for the final state, but the
            // UI waits for the terminal SSE event so the user sees the retry in
            // real time rather than jumping directly from Retry to the result.
            if (!finished) {
                await streamDone;
            }
            return true;
        } catch (error) {
            try { es?.close(); } catch (_) {}
            if (liveResponse.row?.isConnected) {
                liveResponse.state.textContent = 'Retry failed';
                liveResponse.activity.textContent = error?.message || safeUiErrorMessage();
            }
            showToast(error?.message || safeUiErrorMessage());
            return false;
        } finally {
            try { es?.close(); } catch (_) {}
            if (liveResponse.stopButton) { liveResponse.stopButton.disabled = true; }
            if (liveResponse.row?.isConnected) {
                setTimeout(() => removeLiveResponse(liveResponse), 700);
            }
            sourceRow?.querySelectorAll('[data-plan-action]').forEach(btn => {
                btn.disabled = false;
                btn.dataset.busy = 'false';
            });
            submitButton.disabled = false;
            loadingState.classList.remove('visible');
            updateComposerState();
        }
    };

    chatThread.addEventListener('click', async (event) => {
        const button = event.target.closest('[data-stop-run]');
        if (!button || !chatThread.contains(button) || button.disabled) return;
        event.preventDefault();
        event.stopPropagation();
        const row = button.closest('.live-response');
        const threadId = button.dataset.threadId || row?.dataset.threadId;
        const live = row ? { row, state: row.querySelector('.live-state'), activity: row.querySelector('.live-activity-text'), stopButton: button } : null;
        if (!threadId) {
            // The asynchronous start call has not returned its durable thread id
            // yet. Queue the user's Stop intent instead of disabling the only
            // visible control. It will be delivered immediately after /start.
            button.dataset.stopPending = 'true';
            button.disabled = true;
            button.textContent = 'Stop queued…';
            if (live?.state) live.state.textContent = 'Stopping';
            if (live?.activity) live.activity.textContent = 'Stop requested; waiting for the run id…';
            return;
        }
        await requestStopForLive(live, threadId);
    });

    // Decision actions are delegated from the chat thread so they keep working
    // after assistant cards are replaced/re-rendered. The button carries the
    // thread id explicitly, avoiding any dependency on a stale closure.
    chatThread.addEventListener('click', async (event) => {
        const button = event.target.closest('[data-plan-action]');
        if (!button || !chatThread.contains(button)) return;
        event.preventDefault();
        event.stopPropagation();
        if (button.disabled || button.dataset.busy === 'true') return;
        const row = button.closest('.chat-msg.assistant');
        const action = button.getAttribute('data-plan-action');
        const threadId = button.getAttribute('data-thread-id') || row?.dataset.threadId;
        if (!threadId) {
            showToast('This plan no longer has an active decision thread. Please create a new plan.');
            return;
        }
        if (action === 'modify') {
            enterModifyMode(threadId, row);
            return;
        }
        if (action === 'retry') {
            const retryTask = button.getAttribute('data-retry-task') || 'ALL_FAILED';
            await retryWithLiveProgress(threadId, retryTask, row);
            return;
        }
        const endpoint = action === 'approve' ? '/api/plan/approve' : action === 'reject' ? '/api/plan/reject' : null;
        if (!endpoint) return;
        await decide(endpoint, threadId, null, row);
    });

    chatThread.addEventListener('click', (event) => {
        const widgetControl = event.target.closest('[data-widget-action]');
        if (widgetControl) {
            const section = widgetControl.closest('.plan-step');
            if (!section) return;
            const action = widgetControl.dataset.widgetAction;
            const key = section.id.replace(/^section-/, '');
            const title = section.dataset.widgetTitle || key;
            if (action === 'collapse') {
                const collapsed = section.classList.toggle('widget-collapsed');
                if (collapsed) section.classList.remove('widget-expanded');
                updateWidgetFoldState(section);
                return;
            }
            if (action === 'expand') {
                // Expanding a folded widget first unfolds it, then toggles the focused view.
                section.classList.remove('widget-collapsed');
                section.classList.toggle('widget-expanded');
                updateWidgetFoldState(section);
            } else if (action === 'close') {
                section.classList.remove('widget-expanded');
                section.classList.add('widget-closed');
                const workspace = section.closest('.plan-workspace');
                const tray = workspace?.querySelector('[data-widget-restore-tray]');
                if (tray) {
                    let restore = tray.querySelector('[data-restore-key="' + CSS.escape(key) + '"]');
                    if (!restore) {
                        restore = document.createElement('button');
                        restore.type = 'button';
                        restore.className = 'widget-restore-btn';
                        restore.dataset.restoreKey = key;
                        restore.textContent = title;
                        tray.appendChild(restore);
                    }
                    tray.style.display = 'flex';
                }
                const navButton = workspace?.querySelector('[data-section-target="' + CSS.escape(key) + '"]');
                if (navButton) navButton.style.display = 'none';
            }
            return;
        }

        const restoreTrayButton = event.target.closest('[data-restore-key]');
        if (restoreTrayButton) {
            const workspace = restoreTrayButton.closest('.plan-workspace');
            const key = restoreTrayButton.dataset.restoreKey;
            const section = workspace?.querySelector('#section-' + CSS.escape(key));
            if (section) { section.classList.remove('widget-closed', 'widget-collapsed', 'widget-expanded'); updateWidgetFoldState(section); }
            restoreTrayButton.remove();
            if (!event.currentTarget.querySelector('[data-restore-key]')) {
                workspace?.querySelector('[data-widget-restore-tray]')?.style.setProperty('display','none');
            }
            const navButton = workspace?.querySelector('[data-section-target="' + CSS.escape(key) + '"]');
            if (navButton) navButton.style.display = '';
            return;
        }

        const hotelMoreButton = event.target.closest('[data-hotels-more]');
        if (hotelMoreButton) {
            const list = hotelMoreButton.previousElementSibling;
            if (!list || (!list.classList.contains('hotel-list') && !list.classList.contains('hotel-rich-list'))) return;
            const expanded = list.classList.toggle('expanded');
            hotelMoreButton.textContent = expanded
                ? '− Show fewer hotel options'
                : '＋ ' + list.querySelectorAll('.hotel-rich-card.is-extra,.hotel-card.is-extra').length + ' more hotel option' + (list.querySelectorAll('.hotel-rich-card.is-extra,.hotel-card.is-extra').length === 1 ? '' : 's');
            return;
        }

        const flightMoreButton = event.target.closest('[data-flights-more]');
        if (flightMoreButton) {
            const list = flightMoreButton.previousElementSibling;
            if (!list || !list.classList.contains('flight-list')) return;
            const expanded = list.classList.toggle('expanded');
            flightMoreButton.textContent = expanded
                ? '− Show fewer flight options'
                : '＋ ' + list.querySelectorAll('.is-extra-flight').length + ' more available option'
                    + (list.querySelectorAll('.is-extra-flight').length === 1 ? '' : 's');
        }
    });

    const decide = async (url, threadId, notes, sourceRow) => {
        if (!threadId) {
            appendAssistantMessage({ text: 'This plan is no longer available for approval. Please create a new trip plan.' }, false);
            return false;
        }
        loadingState.classList.add('visible');
        submitButton.disabled = true;
        if (sourceRow) {
            sourceRow.querySelectorAll('[data-plan-action]').forEach(btn => {
                btn.disabled = true;
            });
        }
        try {
            const res = await apiFetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId: currentUserId || userIdField.value.trim() || '',
                    threadId,
                    notes
                })
            });
            const payload = await res.json().catch(() => ({}));
            if (!res.ok) {
                throw new Error(payload.error || safeUiErrorMessage());
            }
            if (url.includes('/plan/modify')) {
                if (notes) appendUserMessage(notes);
                appendAssistantMessage(payload);
            } else {
                if (url.includes('/plan/retry')) {
                    appendUserMessage(notes === 'ALL_FAILED' ? 'Retrying all failed tasks.' : 'Retrying ' + humanTaskLabel(notes) + '.');
                } else if (url.includes('reject')) {
                    appendUserMessage('Rejected this plan.');
                } else {
                    appendUserMessage('Approved this plan.');
                }
                replaceDecisionPlanInPlace(sourceRow, payload);
            }
            loadDbTrips(false);
            return true;
        } catch (error) {
            if (sourceRow) {
                sourceRow.querySelectorAll('[data-plan-action]').forEach(btn => {
                    btn.disabled = false;
                    btn.classList.remove('action-unavailable');
                });
            }
            showToast(error && error.message ? error.message : safeUiErrorMessage());
            return false;
        } finally {
            submitButton.disabled = false;
            loadingState.classList.remove('visible');
        }
    };

    promptInput.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter' || event.shiftKey || event.isComposing) {
            return;
        }

        // Enter submits the same way as clicking the visible send button.
        // Use click() rather than requestSubmit() so this remains reliable
        // across browsers and does not depend on form submitter resolution.
        event.preventDefault();
        event.stopPropagation();

        if (!submitButton.disabled && promptInput.value.trim()) {
            submitButton.click();
        }
    });



    

    changePasswordForm?.addEventListener('submit', async (event) => {
        event.preventDefault();
        passwordMessage.textContent = '';
        passwordMessage.className = 'settings-message';
        const currentPassword = currentPasswordInput.value;
        const newPassword = newPasswordInput.value;
        const confirmPassword = confirmNewPasswordInput.value;
        if (newPassword.length < 10 || newPassword.length > 128) {
            passwordMessage.textContent = 'New password must be between 10 and 128 characters.';
            passwordMessage.classList.add('error');
            return;
        }
        if (newPassword !== confirmPassword) {
            passwordMessage.textContent = 'New password and confirmation do not match.';
            passwordMessage.classList.add('error');
            return;
        }
        changePasswordButton.disabled = true;
        try {
            const response = await apiFetch('/api/auth/change-password', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ currentPassword, newPassword })
            });
            const result = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(result.error || 'Unable to change password.');
            changePasswordForm.reset();
            passwordMessage.textContent = result.message || 'Password changed successfully.';
            passwordMessage.classList.add('success');
            showToast('Password changed successfully.');
        } catch (error) {
            passwordMessage.textContent = error.message || 'Unable to change password.';
            passwordMessage.classList.add('error');
        } finally {
            changePasswordButton.disabled = false;
        }
    });

    resizePrompt();
    requestAnimationFrame(resizePrompt);
    // Browser startup always lands on Home. History is loaded into the sidebar,
    // but the most recent conversation is never opened automatically.
    showHomeView();
    clearThreadDom();
    renderHistoryList();

    (async () => {
        try {
            const me = await apiFetch('/api/auth/me', { cache: 'no-store' });
            if (!me.ok) {
                window.location.href = '/login';
                return;
            }
            const identity = await me.json();
            currentUserId = String(identity.userId || identity.username || '').trim();
            if (!currentUserId) {
                window.location.href = '/login';
                return;
            }
            userIdField.value = currentUserId;
            if (signedInUser) signedInUser.textContent = [identity.firstName, identity.lastName].filter(Boolean).join(' ') || currentUserId;
            syncUserIdField();
            await loadDbTrips(false);
            await loadServerHistoryIntoStore();
            renderHistoryList();
        } catch (e) {
            console.error('Authentication bootstrap failed', e);
            window.location.href = '/login';
        }
    })();
