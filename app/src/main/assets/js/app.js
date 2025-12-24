// app.js - 主应用入口 (移动端适配版)
import { useMessageStore } from './messageManager.js?v=2025112722';
import { useScrollManager } from './scrollManager.js?v=2025112722';
import { useInputManager } from './inputManager.js?v=2025112722';
import { useApiService } from './apiService.js?v=2025112722';
import { useVoiceInput } from './voiceInput.js?v=2025112722';
import { useFileUpload } from './fileUpload.js?v=2025112722';
import { useConversationManager } from './conversation.js?v=2025112722';
import { utils } from './utils.js';

const { createApp, ref, onMounted, reactive, nextTick, onUnmounted, toRefs, watch } = Vue;

// 创建应用实例
const app = createApp({
    setup() {
        // 状态管理
        const state = reactive({
            inputMessage: '',
            isSending: false,
            showWelcome: true,
            deepThinkingEnabled: false,
            webSearchEnabled: false,
            isRecording: false,
            sessionId: localStorage.getItem('sessionId') || '',
            userId: 'U1001',
            // 移动端新增状态
            sidebarActive: false,
            currentConversationTitle: '新对话',
            isComposing: false
        });

        // 模块初始化
        const messageStore = useMessageStore(state);
        const scrollManager = useScrollManager();
        const apiService = useApiService(state, messageStore);
        const conversationManager = useConversationManager(state, messageStore, scrollManager, apiService);
        const inputManager = useInputManager(state, messageStore, apiService, scrollManager);
        const voiceInput = useVoiceInput(state, inputManager);
        const fileUpload = useFileUpload(state);

        // 示例问题
        const exampleQuestions = ref([
            { title: "快速复盘", text: "帮我复盘一下本周的工作和学习" },
            { title: "原则查询", text: "请解释P10长远家庭利益原则" },
            { title: "规划建议", text: "为我制定下周的学习计划" },
            { title: "目标设定", text: "如何设定我的3年职业发展目标？" }
        ]);

        // 发送示例问题
        const sendExample = (text) => {
            state.inputMessage = text;
            inputManager.sendMessage();
        };

        // 在app.js的setup函数中
        const handleCompositionStart = () => {
            state.isComposing = true;
        };
        const handleCompositionEnd = () => {
            state.isComposing = false;
        };

        // 切换侧边栏显示 - 修复版本
        const toggleSidebar = () => {
            console.log('toggleSidebar called, current state:', state.sidebarActive);
            state.sidebarActive = !state.sidebarActive;

            // 如果打开侧边栏，刷新对话列表
            if (state.sidebarActive) {
                conversationManager.refreshConversations();
            }

            // 控制body滚动
            if (state.sidebarActive) {
                document.body.style.overflow = 'hidden';
            } else {
                document.body.style.overflow = '';
            }

            // 关闭所有操作菜单
            conversationManager.closeAllActionMenus();
        };

        // 选择对话
        const selectConversation = (conversation) => {
            conversationManager.selectConversation(conversation);
            state.currentConversationTitle = conversation.title;

            // 移动端选择对话后关闭侧边栏
            if (window.innerWidth < 768) {
                state.sidebarActive = false;
                document.body.style.overflow = ''; // 恢复滚动
            }
        };

        // 清空对话
        const clearMessages = async () => {
            messageStore.clearMessages();
            state.showWelcome = true;
            state.inputMessage = '';
            state.currentConversationTitle = '新对话';
            conversationManager.currentSessionId.value = null;

            await apiService.initSession();

            // 强制重置分页状态并刷新对话列表
            conversationManager.pagination.currentPage = 0;
            conversationManager.pagination.hasMore = true;
            conversationManager.loadingState.conversations = false;
            conversationManager.loadingState.loadingMore = false;
            await conversationManager.fetchConversations();

            // 重置滚动状态
            scrollManager.shouldAutoScroll.value = true;
            scrollManager.isUserActivelyScrolling.value = false;

            // 移动端清空对话后关闭侧边栏
            if (window.innerWidth < 768) {
                state.sidebarActive = false;
                document.body.style.overflow = ''; // 恢复滚动
            }

            // 关闭所有操作菜单
            conversationManager.closeAllActionMenus();

            // 重置输入框高度
            nextTick(() => {
                const textarea = document.querySelector('.input-area textarea');
                if (textarea) {
                    textarea.style.height = 'auto';
                    textarea.style.height = '48px';
                }
            });
        };

        // 处理对话列表滚动
        const handleConversationScroll = (event) => {
            conversationManager.handleConversationScroll(event);
        };

        // 窗口大小变化处理
        const handleResize = () => {
            if (window.innerWidth >= 768) {
                state.sidebarActive = false;
                document.body.style.overflow = ''; // 恢复滚动
            }
            // 更新移动端状态
            conversationManager.checkIsMobile();
        };

        // 右键菜单处理
        const handleContextMenu = (event, conversation) => {
            conversationManager.handleContextMenu(event, conversation);
        };

        // 触摸开始处理
        const handleTouchStart = (conversationId) => {
            conversationManager.handleTouchStart(conversationId);
        };

        // 触摸结束处理
        const handleTouchEnd = () => {
            conversationManager.handleTouchEnd();
        };

        // 切换操作菜单
        const toggleActionMenu = (conversationId) => {
            conversationManager.toggleActionMenu(conversationId);
        };

        // 关闭操作菜单
        const closeActionMenu = (conversationId) => {
            conversationManager.closeActionMenu(conversationId);
        };

        // 关闭所有操作菜单
        const closeAllActionMenus = () => {
            conversationManager.closeAllActionMenus();
        };

        // 删除对话
        const deleteConversation = (conversation) => {
            conversationManager.deleteConversation(conversation);
        };

        // 通过ID删除对话
        const deleteConversationById = (conversationId) => {
            conversationManager.deleteConversationById(conversationId);
        };

        // 获取对话标题
        const getConversationTitle = (conversationId) => {
            return conversationManager.getConversationTitle(conversationId);
        };

        // 添加验证 sessionId 有效性的方法
        const validateSessionId = async (sessionId) => {
            try {
                const response = await fetch('/xiaoXia/validateSession', {
                    method: 'GET',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-Session-Id': sessionId
                    }
                });

                const result = await response.json();
                // 如果返回 200 表示 sessionId 有效
                return result.sessionId !== "";
            } catch (error) {
                console.error('验证 sessionId 失败:', error);
                return false;
            }
        };

        // 先确保会话已初始化
        const initializeApp = async () => {
            // 检查现有 sessionId 是否有效
            if (state.sessionId && state.sessionId.trim() !== '') {
                const isValid = await validateSessionId(state.sessionId);
                console.log('sessionId 是否有效:', isValid)
                if (!isValid) {
                    await apiService.initSession();
                }
            } else {
                await apiService.initSession();
            }

            // 然后初始化对话管理器
            conversationManager.init();
        };

        // 监听消息变化，自动滚动
        watch(() => messageStore.messages.value, () => {
            nextTick(() => {
                scrollManager.scrollToBottom();
            });
        }, { deep: true });

        // 监听单个消息内容变化，用于流式响应
        watch(() => messageStore.messages.value.map(msg => ({
            content: msg.content,
            reasoningContent: msg.reasoningContent
        })), () => {
            nextTick(() => {
                scrollManager.scrollToBottom();
            });
        }, { deep: true });

        // 监听对话选择，更新标题
        watch(() => conversationManager.currentSessionId.value, (newSessionId) => {
            if (newSessionId) {
                const conversation = conversationManager.conversations.value.find(
                    conv => conv.id === newSessionId
                );
                if (conversation) {
                    state.currentConversationTitle = conversation.title;
                }
            } else {
                state.currentConversationTitle = '新对话';
            }
        });

        // 监听侧边栏状态变化，关闭操作菜单
        watch(() => state.sidebarActive, (newValue) => {
            if (!newValue) {
                conversationManager.closeAllActionMenus();
            }
        });

        // 生命周期
        onMounted(() => {
            scrollManager.init();
            initializeApp();

            // 添加窗口大小变化监听
            window.addEventListener('resize', handleResize);

            // 添加点击外部关闭菜单的全局监听
            document.addEventListener('click', (event) => {
                // 如果点击的不是操作菜单相关元素，则关闭所有菜单
                if (!event.target.closest('.conversation-actions') &&
                    !event.target.closest('.action-menu') &&
                    !event.target.closest('.mobile-action-menu')) {
                    conversationManager.closeAllActionMenus();
                }
            });
        });

        onUnmounted(() => {
            scrollManager.cleanup();
            voiceInput.cleanup();
            conversationManager.cleanup();
            // 移除窗口大小变化监听
            window.removeEventListener('resize', handleResize);
            // 确保恢复body滚动
            document.body.style.overflow = '';
        });

        // 监听对话列表更新后重新初始化观察器
        watch(() => conversationManager.conversations.value, () => {
            nextTick(() => {
                conversationManager.reInitObserver();
            });
        });

        return {
            ...toRefs(state),
            ...messageStore,
            ...scrollManager,
            ...inputManager,
            ...voiceInput,
            ...fileUpload,
            ...conversationManager,
            exampleQuestions,
            sendExample,
            clearMessages,
            toggleSidebar,
            selectConversation,
            handleConversationScroll,
            handleCompositionStart,
            handleCompositionEnd,
            // 新增的操作菜单方法
            handleContextMenu,
            handleTouchStart,
            handleTouchEnd,
            toggleActionMenu,
            closeActionMenu,
            closeAllActionMenus,
            deleteConversation,
            deleteConversationById,
            getConversationTitle,
            utils
        };
    }
});

// 注册点击外部指令
app.directive('click-outside', {
    beforeMount(el, binding) {
        el.clickOutsideEvent = function(event) {
            // 检查点击是否发生在元素外部
            if (!(el === event.target || el.contains(event.target))) {
                binding.value(event);
            }
        };
        // 添加事件监听器
        document.addEventListener('click', el.clickOutsideEvent);
    },
    unmounted(el) {
        // 清理事件监听器
        document.removeEventListener('click', el.clickOutsideEvent);
    }
});

// 挂载应用
app.mount('#app');