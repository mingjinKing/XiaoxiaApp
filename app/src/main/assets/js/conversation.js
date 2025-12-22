// modules/conversation.js - 对话历史管理（支持无限滚动）

const { ref, reactive, onMounted, onUnmounted } = Vue;

export const useConversationManager = (state, messageStore, scrollManager, apiService) => {
    // 会话列表
    const conversations = ref([]);
    // 当前选中的会话ID
    const currentSessionId = ref(null);
    // 加载状态
    const loadingState = reactive({
        conversations: false,
        conversationTurns: false,
        loadingMore: false,
        loadingSession: false // 新增：加载会话状态
    });

    // 操作菜单状态
    const actionMenuStates = ref({});
    const currentActionMenu = ref(null);
    const isMobile = ref(false);
    const longPressTimer = ref(null);

    // 检测屏幕大小
    const checkIsMobile = () => {
        isMobile.value = window.innerWidth < 768;
    };

    // 处理对话列表滚动
    const handleConversationScroll = (event) => {
        const { scrollTop, scrollHeight, clientHeight } = event.target;
        // 可以在这里添加滚动处理逻辑，比如虚拟滚动优化等
    };

    // 分页信息
    const pagination = reactive({
        currentPage: 0,
        pageSize: 20,
        hasMore: true,
        total: 0
    });

    // 观察器相关
    let observer = null;
    const sentinel = ref(null);

    // 新增：加载会话到缓存
    const loadSessionToCache = async (sessionId) => {
        if (!sessionId) {
            console.error('sessionId 不能为空');
            return false;
        }

        loadingState.loadingSession = true;

        try {
            console.log(`开始加载会话 ${sessionId} 到缓存...`);
            // 在所有fetch请求前添加baseUrl
            const API_BASE_URL = window.APP_CONFIG?.baseUrl || "https://derbi.net.cn";
            const response = await fetch(`${API_BASE_URL}/xiaoXia/loadSession?sessionId=${sessionId}`, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json',
                    'X-Session-Id': state.sessionId
                }
            });

            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`HTTP ${response.status}: ${errorText}`);
            }

            const result = await response.json();
            console.log('会话加载到缓存成功:', result);

            return true;

        } catch (error) {
            console.error('加载会话到缓存出错:', error);
            // 这里可以选择是否抛出错误，或者只是记录日志
            return false;
        } finally {
            loadingState.loadingSession = false;
        }
    };

    // 获取会话列表
    const fetchConversations = async (loadMore = false) => {
        if (loadingState.conversations && !loadMore) return;
        if (loadingState.loadingMore && loadMore) return;
        if (!loadMore && !pagination.hasMore) return;

        if (loadMore) {
            loadingState.loadingMore = true;
            pagination.currentPage++;
        } else {
            loadingState.conversations = true;
            pagination.currentPage = 0;
            pagination.hasMore = true;
            conversations.value = [];
        }

        try {
            // 在所有fetch请求前添加baseUrl
            const API_BASE_URL = window.APP_CONFIG?.baseUrl || "https://derbi.net.cn";
            const response = await fetch(`${API_BASE_URL}/xiaoXia/sessions?page=${pagination.currentPage}&size=${pagination.pageSize}`, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json',
                    'X-Session-Id': state.sessionId
                }
            });

            if (!response.ok) {
                throw new Error(`获取会话列表失败: ${response.status}`);
            }

            const data = await response.json();

            // 假设后端返回结构为 { content: [], totalElements: number, last: boolean }
            const sessionList = data.content || data;
            const totalElements = data.totalElements || data.length;
            const isLastPage = data.last !== undefined ? data.last : (sessionList.length < pagination.pageSize);

            const formattedSessions = sessionList.map(session => ({
                id: session.id,
                title: session.title || '无标题对话',
                summary: formatSummary(session.summary),
                createdAt: session.createdAt,
                updatedAt: session.updatedAt,
                isExpired: session.isExpired || false
            }));

            if (loadMore) {
                conversations.value = [...conversations.value, ...formattedSessions];
            } else {
                conversations.value = formattedSessions;
            }

            pagination.hasMore = !isLastPage;
            pagination.total = totalElements;

            console.log(`会话列表加载成功: 第${pagination.currentPage}页, ${formattedSessions.length}条记录`);

        } catch (error) {
            console.error('获取会话列表出错:', error);
            if (loadMore) {
                pagination.currentPage--; // 回滚页码
            }
        } finally {
            loadingState.conversations = false;
            loadingState.loadingMore = false;
        }
    };

    // 格式化摘要（超过10个字用省略号）
    const formatSummary = (summary) => {
        if (!summary) return '暂无摘要';
        if (summary.length <= 10) return summary;
        return summary.substring(0, 10) + '...';
    };

    // 操作菜单管理
    const toggleActionMenu = (conversationId) => {
        // 关闭其他菜单
        Object.keys(actionMenuStates.value).forEach(key => {
            if (key !== conversationId) {
                actionMenuStates.value[key] = false;
            }
        });

        // 切换当前菜单
        actionMenuStates.value[conversationId] = !actionMenuStates.value[conversationId];

        // 更新当前操作菜单
        if (actionMenuStates.value[conversationId]) {
            currentActionMenu.value = conversationId;
        } else {
            currentActionMenu.value = null;
        }
    };

    const closeActionMenu = (conversationId) => {
        actionMenuStates.value[conversationId] = false;
        if (currentActionMenu.value === conversationId) {
            currentActionMenu.value = null;
        }
    };

    const closeAllActionMenus = () => {
        Object.keys(actionMenuStates.value).forEach(key => {
            actionMenuStates.value[key] = false;
        });
        currentActionMenu.value = null;
    };

    // 移动端长按处理
    const handleTouchStart = (conversationId) => {
        if (!isMobile.value) return;

        longPressTimer.value = setTimeout(() => {
            console.log('长按触发', conversationId, isMobile)
            closeAllActionMenus(); // 先关闭所有菜单
            // 显示移动端操作菜单
            currentActionMenu.value = conversationId;
        }, 500); // 500ms长按触发
    };

    const handleTouchEnd = () => {
        if (longPressTimer.value) {
            clearTimeout(longPressTimer.value);
            longPressTimer.value = null;
        }
    };

    // 右键菜单处理
    const handleContextMenu = (event, conversation) => {
        event.preventDefault();
        if (!isMobile.value) {
            toggleActionMenu(conversation.id);
        }
    };

    // 删除对话
    const deleteConversation = async (conversation) => {
        try {
            // 在所有fetch请求前添加baseUrl
            const API_BASE_URL = window.APP_CONFIG?.baseUrl || "https://derbi.net.cn";
            const response = await fetch(`${API_BASE_URL}/xiaoXia/conversationTurns?sessionId=${conversation.id}`, {
                method: 'DELETE',
                headers: {
                    'X-Session-Id': state.sessionId
                }
            });

            if (!response.ok) {
                throw new Error('删除失败');
            }

            // 从列表中移除
            const index = conversations.value.findIndex(c => c.id === conversation.id);
            if (index !== -1) {
                conversations.value.splice(index, 1);
            }

            // 如果删除的是当前选中的会话，则清空消息并显示欢迎页
            if (currentSessionId.value === conversation.id) {
                currentSessionId.value = null;
                state.sessionId = null;
                localStorage.removeItem('sessionId');
                messageStore.clearMessages();
                state.showWelcome = true;
            }

            // 关闭菜单
            closeAllActionMenus();

            console.log('对话删除成功:', conversation.id);

        } catch (error) {
            console.error('删除对话出错:', error);
            alert('删除失败，请重试');
        }
    };

    // 通过ID删除对话
    const deleteConversationById = (conversationId) => {
        const conversation = conversations.value.find(c => c.id === conversationId);
        if (conversation) {
            deleteConversation(conversation);
        }
    };

    // 获取对话标题
    const getConversationTitle = (conversationId) => {
        const conversation = conversations.value.find(c => c.id === conversationId);
        return conversation ? conversation.title : '未知对话';
    };

    // 根据会话ID获取对话轮次
    const fetchConversationTurns = async (sessionId) => {
        if (!sessionId) {
            console.error('sessionId 不能为空');
            return;
        }

        loadingState.conversationTurns = true;
        currentSessionId.value = sessionId;

        try {
            console.log(`开始获取会话 ${sessionId} 的对话记录...`);
            // 在所有fetch请求前添加baseUrl
            const API_BASE_URL = window.APP_CONFIG?.baseUrl || "https://derbi.net.cn";
            const response = await fetch(`${API_BASE_URL}/xiaoXia/conversationTurns?sessionId=${sessionId}`, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json',
                    'X-Session-Id': state.sessionId
                }
            });

            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`HTTP ${response.status}: ${errorText}`);
            }

            const data = await response.json();
            console.log('原始响应数据:', data);

            // 多种方式尝试获取 turns 数据
            let turns = [];
            if (data.turns && Array.isArray(data.turns)) {
                turns = data.turns;
            } else if (Array.isArray(data)) {
                turns = data;
            } else if (data.content && Array.isArray(data.content)) {
                turns = data.content;
            } else if (data.data && Array.isArray(data.data)) {
                turns = data.data;
            }

            console.log(`解析出的对话轮次数量: ${turns.length}`);

            if (turns.length === 0) {
                console.log('该会话暂无对话记录');
                messageStore.addAIResponseMessage('该会话暂无对话记录');
                return;
            }

            // 清空当前消息
            messageStore.clearMessages();
            state.showWelcome = false;

            // 按轮次序号排序
            const sortedTurns = turns.sort((a, b) => {
                // 优先使用 turnNumber，其次使用 id，最后使用 createdAt
                const orderA = a.turnNumber !== undefined ? a.turnNumber :
                    (a.id !== undefined ? a.id :
                        (a.createdAt ? new Date(a.createdAt).getTime() : 0));
                const orderB = b.turnNumber !== undefined ? b.turnNumber :
                    (b.id !== undefined ? b.id :
                        (b.createdAt ? new Date(b.createdAt).getTime() : 0));
                return orderA - orderB;
            });

            // 统计消息数量
            let userMessageCount = 0;
            let aiMessageCount = 0;

            // 加载对话记录到消息列表
            sortedTurns.forEach((turn, index) => {
                console.log(`处理第 ${index + 1} 条轮次:`, turn);

                // 添加用户消息
                if (turn.userMessage && turn.userMessage.trim() !== '') {
                    messageStore.addUserMessage(turn.userMessage);
                    userMessageCount++;
                }

                // 添加AI回复消息
                if (turn.assistantResponse && turn.assistantResponse.trim() !== '') {
                    messageStore.addAIResponseMessage(turn.assistantResponse);
                    aiMessageCount++;

                    // 完成AI消息（设置为完成状态）
                    const lastIndex = messageStore.getLastMessageIndex();
                    if (lastIndex >= 0) {
                        messageStore.completeAIMessage(lastIndex);
                    }
                }
            });

            // 更新当前会话ID
            state.sessionId = sessionId;
            localStorage.setItem('sessionId', sessionId);

            console.log(`成功加载对话记录: ${userMessageCount} 条用户消息, ${aiMessageCount} 条AI回复`);

            Vue.nextTick(() => {
                setTimeout(() => {
                    scrollManager.forceScrollToBottom();
                }, 50);
            });

        } catch (error) {
            console.error('获取对话记录出错:', error);
            // 添加错误提示消息
            messageStore.addAIResponseMessage(`加载历史对话失败: ${error.message}`);

            // 确保清空加载状态
            messageStore.clearMessages();
            state.showWelcome = true;
        } finally {
            loadingState.conversationTurns = false;
        }
    };

    // 选择会话 - 已修改：添加加载会话到缓存的功能
    const selectConversation = async (conversation) => {
        if (conversation.id === currentSessionId.value) return;

        // 关闭所有操作菜单
        closeAllActionMenus();

        try {
            // 先获取对话轮次
            await fetchConversationTurns(conversation.id);

            // 后加载会话到缓存
            const cacheLoaded = await loadSessionToCache(conversation.id);

            if (!cacheLoaded) {
                console.warn('对话已加载，但会话缓存加载失败');
            }
        } catch (error) {
            console.error('选择会话过程中出错:', error);
            // 可以在这里添加用户提示
            messageStore.addAIResponseMessage('加载会话失败，请重试');
        }
    };

    // 判断会话是否被选中
    const isConversationSelected = (conversation) => {
        return conversation.id === currentSessionId.value;
    };

    // 刷新会话列表
    const refreshConversations = () => {
        fetchConversations(false);
    };

    // 初始化 Intersection Observer
    const initObserver = () => {
        if (!sentinel.value) return;

        observer = new IntersectionObserver(
            (entries) => {
                const [entry] = entries;
                if (entry.isIntersecting && pagination.hasMore && !loadingState.loadingMore) {
                    console.log('触发加载更多...');
                    fetchConversations(true);
                }
            },
            {
                root: document.querySelector('.conversation-items'),
                rootMargin: '100px', // 提前100px触发加载
                threshold: 0.1
            }
        );

        observer.observe(sentinel.value);
    };

    // 销毁观察器
    const destroyObserver = () => {
        if (observer) {
            observer.disconnect();
            observer = null;
        }
    };

    // 初始化
    const init = () => {
        checkIsMobile();
        window.addEventListener('resize', checkIsMobile);

        fetchConversations(false);

        // 在下一个tick初始化观察器，确保DOM已渲染
        Vue.nextTick(() => {
            initObserver();
        });
    };

    // 清理
    const cleanup = () => {
        destroyObserver();
        window.removeEventListener('resize', checkIsMobile);
        if (longPressTimer.value) {
            clearTimeout(longPressTimer.value);
        }
    };

    // 重新初始化观察器（当DOM更新后调用）
    const reInitObserver = () => {
        destroyObserver();
        Vue.nextTick(() => {
            initObserver();
        });
    };

    return {
        conversations,
        currentSessionId,
        loadingState,
        pagination,
        sentinel,
        actionMenuStates,
        currentActionMenu,
        isMobile,
        fetchConversations,
        fetchConversationTurns,
        selectConversation,
        isConversationSelected,
        refreshConversations,
        handleConversationScroll,
        toggleActionMenu,
        closeActionMenu,
        closeAllActionMenus,
        handleTouchStart,
        handleTouchEnd,
        handleContextMenu,
        deleteConversation,
        deleteConversationById,
        getConversationTitle,
        loadSessionToCache, // 新增：导出加载会话方法
        init,
        reInitObserver,
        destroyObserver,
        checkIsMobile,
        cleanup
    };
};
