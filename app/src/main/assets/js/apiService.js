// modules/apiService.js - API服务

const {ref} = Vue;
export const useApiService = (state, messageStore) => {
    const apiUrl = ref('https://deri.net.cn/xiaoXia/textChat');
    let abortController = null;

    // 初始化会话
    const initSession = async () => {
        try {
            const headers = {
                'Content-Type': 'application/json'
            };
            if (state.sessionId) {
                headers['X-Session-Id'] = state.sessionId;
            }
            // 在所有fetch请求前添加baseUrl
            const API_BASE_URL = window.APP_CONFIG?.baseUrl || "https://derbi.net.cn";
            const response = await fetch('${API_BASE_URL}/xiaoXia/initSession', {
                method: 'POST',
                headers: headers,
                body: JSON.stringify({
                    userId: state.userId
                })
            });

            if (!response.ok) {
                throw new Error(`初始化会话失败: ${response.status}`);
            }

            const newSessionId = response.headers.get('X-Session-Id');
            if (newSessionId) {
                state.sessionId = newSessionId;
                localStorage.setItem('sessionId', newSessionId);
                console.log('会话初始化成功，Session ID:', newSessionId);
            }
        } catch (error) {
            console.error('初始化会话出错:', error);
        }
    };

    // 发送消息
    const sendMessage = async (message, options = {}) => {
        abortController = new AbortController();
        let hasStartedContent = false;
        let aiMessageIndex = -1;
        let hasValidContent = false; // 标记是否收到过有效内容

        try {
            const response = await fetch(apiUrl.value, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-Session-Id': state.sessionId
                },
                body: JSON.stringify({
                    reqMessage: message,
                    userId: state.userId,
                    ...options
                }),
                signal: abortController.signal
            });

            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }

            const newSessionId = response.headers.get('X-Session-Id');
            if (newSessionId && newSessionId !== state.sessionId) {
                state.sessionId = newSessionId;
                localStorage.setItem('sessionId', newSessionId);
            }

            await processSSEResponse(response, (data) => {
                // 检查是否是有实际内容的数据（非空文本或思考内容）
                const hasRealContent = data.content && data.content.trim() !== '';
                const hasRealReasoning = data.reasoning_content && data.reasoning_content.trim() !== '';

                if (hasRealContent || hasRealReasoning) {
                    hasValidContent = true;
                }

                if (!hasStartedContent) {
                    messageStore.removeLastMessage();
                    messageStore.addAIResponseMessage(
                        data.content || '',
                        data.reasoning_content || '',
                        data.chartOption || null
                    );
                    aiMessageIndex = messageStore.getLastMessageIndex();
                    hasStartedContent = true;

                    // 如果非深度思考模式且没有实际思考内容，立即隐藏思考区域
                    if (!options.deepThinking && !hasRealReasoning) {
                        messageStore.messages.value[aiMessageIndex].showReasoning = false;
                        messageStore.messages.value[aiMessageIndex].isReceivingReasoning = false;
                    }
                } else {
                    if (data.content) {
                        messageStore.updateMessageContent(aiMessageIndex, data.content, false);
                    }
                    if (data.reasoning_content) {
                        messageStore.updateMessageContent(aiMessageIndex, data.reasoning_content, true);
                        // 只有在深度思考模式下才保持 isReceivingReasoning 状态
                        if (options.deepThinking) {
                            messageStore.messages.value[aiMessageIndex].isReceivingReasoning = false;
                        }
                    }

                    // 非深度思考模式下，如果一直没有收到思考内容，就隐藏思考区域
                    if (!options.deepThinking && !hasRealReasoning) {
                        messageStore.messages.value[aiMessageIndex].showReasoning = false;
                        messageStore.messages.value[aiMessageIndex].isReceivingReasoning = false;
                    }
                }
            });

            if (!hasStartedContent) {
                messageStore.removeLastMessage();
                messageStore.addAIResponseMessage('收到空响应，请重试或联系支持');
            } else {
                // 完成消息时，如果非深度思考模式且没有有效思考内容，确保思考区域隐藏
                if (!options.deepThinking && !hasValidContent) {
                    messageStore.messages.value[aiMessageIndex].showReasoning = false;
                    messageStore.messages.value[aiMessageIndex].isReceivingReasoning = false;
                }
                messageStore.completeAIMessage(aiMessageIndex);
            }

        } catch (error) {
            if (error.name !== 'AbortError') {
                throw error;
            } else {
                console.log('请求已被用户取消');
                // 如果请求被取消，移除加载消息
                messageStore.removeLastMessage();
            }
        } finally {
            abortController = null;
        }
    };

    // 处理SSE响应
    const processSSEResponse = async (response, onData) => {
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        try {
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                buffer += decoder.decode(value, { stream: true });

                let eventEndIndex;
                while ((eventEndIndex = buffer.indexOf('\n\n')) !== -1) {
                    const eventData = buffer.substring(0, eventEndIndex);
                    buffer = buffer.substring(eventEndIndex + 2);

                    const lines = eventData.split('\n');
                    for (const line of lines) {
                        if (line.startsWith('data:')) {
                            const data = line.substring(5).trim();
                            if (data === '[DONE]') {
                                break;
                            }

                            try {
                                const parsed = parseSSEData(data);
                                if (parsed) {
                                    onData(parsed);
                                }
                            } catch (e) {
                                console.error('解析JSON失败:', e);
                            }
                        }
                    }
                }
            }
        } catch (error) {
            if (error.name !== 'AbortError') {
                throw error;
            }
        }
    };

    // 解析SSE数据 - 增强版本
    const parseSSEData = (data) => {
        try {
            const parsed = JSON.parse(data);
            let content = parsed.output?.text || '';
            let reasoningContent = '';

            // 过滤掉只有 usage 信息的空数据包
            const hasRealContent = content && content.trim() !== '';
            const hasRealThoughts = parsed.output?.thoughts && Array.isArray(parsed.output.thoughts) && parsed.output.thoughts.length > 0;

            if (hasRealThoughts) {
                parsed.output.thoughts.forEach(thought => {
                    if (thought.actionType === 'reasoning' && thought.response && thought.response.trim() !== '') {
                        reasoningContent += thought.response;
                    }
                });
            }

            // 如果既没有实际内容也没有实际思考内容，返回 null 表示跳过此数据包
            if (!hasRealContent && !reasoningContent) {
                return null;
            }

            return {
                content: content,
                reasoning_content: reasoningContent,
                chartOption: null
            };
        } catch (e) {
            console.error('解析JSON失败:', e, '原始数据:', data);
            return null;
        }
    };

    // 取消请求
    const abortRequest = () => {
        if (abortController) {
            abortController.abort();
            abortController = null;
            return true;
        }
        return false;
    };

    // 检查是否有进行中的请求
    const hasActiveRequest = () => {
        return abortController !== null;
    };

    return {
        initSession,
        sendMessage,
        abortRequest,
        hasActiveRequest
    };
};