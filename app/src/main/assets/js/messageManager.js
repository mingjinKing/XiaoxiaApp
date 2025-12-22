// modules/messageManager.js - 消息管理

const {ref} = Vue;
export const useMessageStore = (state) => {
    const messages = ref([]);

    // 添加用户消息
    const addUserMessage = (content) => {
        messages.value.push({
            role: 'user',
            type: 'text',
            content: content,
            showDisclaimer: false,
            timestamp: Date.now()
        });
    };

    // 添加AI加载消息
    const addAILoadingMessage = () => {
        messages.value.push({
            role: 'assistant',
            type: 'loading',
            content: '',
            timestamp: Date.now()
        });
    };

    // 添加AI响应消息
    const addAIResponseMessage = (content, reasoningContent = '', chartOption = null) => {
        // 检查是否有真实的思考内容
        const hasRealReasoning = reasoningContent && reasoningContent.trim() !== '';

        messages.value.push({
            role: 'assistant',
            type: chartOption ? 'chart' : 'text',
            content: content,
            reasoningContent: reasoningContent,
            showReasoning: hasRealReasoning, // 只有有真实思考内容时才显示思考区域
            isReceivingReasoning: hasRealReasoning, // 只有有真实思考内容时才显示思考中状态
            chartOption: chartOption,
            showDisclaimer: false,
            timestamp: Date.now()
        });
    };

    // 更新消息内容
    const updateMessageContent = (messageIndex, newContent, isReasoning = false) => {
        if (messageIndex < 0 || messageIndex >= messages.value.length) return;

        const message = messages.value[messageIndex];
        if (isReasoning) {
            message.reasoningContent += newContent;

            // 如果更新后思考内容不为空，确保思考区域显示
            if (newContent && newContent.trim() !== '') {
                message.showReasoning = true;
            }
        } else {
            message.content += newContent;
        }

        // 触发响应式更新
        messages.value[messageIndex] = { ...message };
    };

    // 移除最后一条消息
    const removeLastMessage = () => {
        messages.value.pop();
    };

    // 完成AI消息
    const completeAIMessage = (messageIndex) => {
        if (messageIndex >= 0 && messageIndex < messages.value.length) {
            const message = messages.value[messageIndex];
            message.showDisclaimer = true;
            message.isReceivingReasoning = false;

            // 如果最终没有思考内容，隐藏思考区域
            if (!message.reasoningContent || message.reasoningContent.trim() === '') {
                message.showReasoning = false;
            }
        }
    };

    // 清空所有消息
    const clearMessages = () => {
        messages.value = [];
        state.showWelcome = true;
    };

    // 获取消息索引
    const getLastMessageIndex = () => {
        return messages.value.length - 1;
    };

    return {
        messages,
        addUserMessage,
        addAILoadingMessage,
        addAIResponseMessage,
        updateMessageContent,
        removeLastMessage,
        completeAIMessage,
        clearMessages,
        getLastMessageIndex
    };
};