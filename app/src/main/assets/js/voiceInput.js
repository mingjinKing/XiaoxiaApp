// modules/voiceInput.js - 语音输入
const {ref} = Vue;

export const useVoiceInput = (state, inputManager) => {
    const isRecording = ref(false);
    const recognition = ref(null);

    // 初始化语音识别
    const initVoiceRecognition = () => {
        if ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window) {
            const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            recognition.value = new SpeechRecognition();

            recognition.value.continuous = false;
            recognition.value.interimResults = true;
            recognition.value.lang = 'zh-CN';

            recognition.value.onstart = () => {
                isRecording.value = true;
            };

            recognition.value.onresult = (event) => {
                let interimTranscript = '';
                let finalTranscript = '';

                for (let i = event.resultIndex; i < event.results.length; i++) {
                    const transcript = event.results[i][0].transcript;
                    if (event.results[i].isFinal) {
                        finalTranscript += transcript;
                    } else {
                        interimTranscript += transcript;
                    }
                }

                if (finalTranscript) {
                    state.inputMessage += finalTranscript;
                } else if (interimTranscript) {
                    // 实时显示临时结果（可选）
                    // state.inputMessage = interimTranscript;
                }
            };

            recognition.value.onerror = (event) => {
                console.error('语音识别错误:', event.error);
                isRecording.value = false;

                // 显示错误提示
                if (event.error === 'not-allowed') {
                    alert('请允许浏览器使用麦克风权限');
                }
            };

            recognition.value.onend = () => {
                isRecording.value = false;
            };
        } else {
            console.warn('浏览器不支持语音识别');
            alert('您的浏览器不支持语音识别功能');
        }
    };

    // 切换语音输入
    const toggleVoiceInput = () => {
        if (!recognition.value) {
            initVoiceRecognition();
        }

        if (isRecording.value) {
            stopVoiceInput();
        } else {
            startVoiceInput();
        }
    };

    // 开始语音输入
    const startVoiceInput = () => {
        if (recognition.value) {
            try {
                recognition.value.start();
            } catch (error) {
                console.error('启动语音识别失败:', error);
            }
        }
    };

    // 停止语音输入
    const stopVoiceInput = () => {
        if (recognition.value) {
            recognition.value.stop();
        }
    };

    // 清理
    const cleanup = () => {
        if (recognition.value) {
            recognition.value.stop();
        }
    };

    return {
        isRecording,
        toggleVoiceInput,
        startVoiceInput,
        stopVoiceInput,
        cleanup
    };
};