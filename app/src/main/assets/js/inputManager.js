// modules/inputManager.js - 输入管理

const {nextTick} = Vue;
export const useInputManager = (state, messageStore, apiService, scrollManager) => {

    const isMobileDevice = () => {
        // 屏幕尺寸检测
        return  window.innerWidth < 768;
    };

    // 处理文本域回车键
    const handleTextareaEnter = (event) => {
        // 移动端：允许直接按Enter键换行
        if (isMobileDevice()) {
            return; // 不阻止默认行为，允许换行
        }

        // PC端：如果正在输入法组合中，允许默认行为（选择候选词）
        if (state.isComposing) {
            return;
        }

        if (event.shiftKey) {
            // Shift+Enter 换行
            return;
        } else {
            // Enter 发送消息
            event.preventDefault();
            if (state.isSending) {
                cancelRequest();
            } else {
                sendMessage();
            }
        }
    };

    // 检测是否为移动设备


    // 调整文本域高度
    const adjustTextareaHeight = (event) => {
        const textarea = event.target;
        textarea.style.height = 'auto';
        textarea.style.height = Math.min(textarea.scrollHeight, 300) + 'px';
    };

    // 发送消息
    const sendMessage = async () => {
        if (!state.inputMessage.trim() || state.isSending) return;

        const messageContent = state.inputMessage.trim();
        state.inputMessage = '';
        state.isSending = true;
        state.showWelcome = false;

        // 添加用户消息
        messageStore.addUserMessage(messageContent);

        // 强制滚动到底部显示用户消息
        nextTick(() => {
            scrollManager.forceScrollToBottom();
        });

        // 添加AI加载消息
        messageStore.addAILoadingMessage();

        // 再次滚动显示加载消息
        nextTick(() => {
            scrollManager.forceScrollToBottom();
        });

        // 重置文本域高度
        nextTick(() => {
            const textarea = document.querySelector('.input-area textarea');
            if (textarea) {
                textarea.style.height = '48px';
            }
        });

        try {
            await apiService.sendMessage(messageContent, {
                deepThinking: state.deepThinkingEnabled,
                webSearch: state.webSearchEnabled
            });
        } catch (error) {
            if (error.name !== 'AbortError') {
                console.error('发送消息失败:', error);
                messageStore.removeLastMessage();
                messageStore.addAIResponseMessage('抱歉，处理您的请求时出错了。请稍后再试。');
                nextTick(() => {
                    scrollManager.scrollToBottom();
                });
            }
        } finally {
            state.isSending = false;
        }
    };

    // 取消请求
    const cancelRequest = () => {
        if (apiService.abortRequest()) {
            state.isSending = false;
            console.log('请求已取消');
        }
    };

    return {
        handleTextareaEnter,
        adjustTextareaHeight,
        sendMessage,
        cancelRequest
    };
};