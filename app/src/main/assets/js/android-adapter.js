// android-adapter.js - 添加到assets/js目录
/**
 * Android平台适配器
 */
(function() {
    'use strict';

    // 检测Android平台
    const isAndroid = !!window.AndroidInterface ||
                     navigator.userAgent.toLowerCase().indexOf('android') > -1;

    if (!isAndroid) return;

    console.log('Android平台适配器启动');

    // 重写API服务以使用Android接口
    const originalApiService = window.ApiService;
    if (originalApiService) {
        class AndroidApiService extends originalApiService.constructor {
            async request(endpoint, options = {}) {
                // 添加Android特定头信息
                options.headers = {
                    ...options.headers,
                    'X-Platform': 'android',
                    'X-Device-Model': window.APP_CONFIG?.deviceInfo?.model || 'Android'
                };

                try {
                    return await super.request(endpoint, options);
                } catch (error) {
                    // Android特定错误处理
                    console.error('Android API请求失败:', error);

                    // 检查网络连接
                    if (window.AndroidInterface && window.AndroidInterface.isOnline) {
                        const isOnline = window.AndroidInterface.isOnline();
                        if (!isOnline) {
                            throw new Error('网络连接不可用');
                        }
                    }

                    throw error;
                }
            }

            async streamRequest(endpoint, options = {}, onChunk) {
                // Android上的流式请求处理
                if (window.AndroidInterface && window.AndroidInterface.handleStreamRequest) {
                    // 使用Android原生处理流式请求
                    return new Promise((resolve, reject) => {
                        window.AndroidInterface.handleStreamRequest(
                            endpoint,
                            JSON.stringify(options),
                            (chunk) => onChunk(chunk),
                            () => resolve(),
                            (error) => reject(new Error(error))
                        );
                    });
                } else {
                    // 回退到标准实现
                    return super.streamRequest(endpoint, options, onChunk);
                }
            }
        }

        // 替换全局ApiService实例
        const sessionManager = window.SessionManager;
        window.ApiService = new AndroidApiService(
            window.APP_CONFIG?.apiBaseUrl || 'https://derbi.net.cn',
            sessionManager
        );
    }

    // 文件上传适配
    if (window.FileUploadManager) {
        const originalUpload = window.FileUploadManager.upload;
        window.FileUploadManager.upload = function(file) {
            return new Promise((resolve, reject) => {
                if (window.AndroidInterface && window.AndroidInterface.pickFile) {
                    // 使用Android文件选择器
                    window.AndroidInterface.pickFile(
                        file.type || '*/*',
                        (fileData) => {
                            // 处理Android返回的文件数据
                            resolve({
                                url: fileData.url,
                                name: fileData.name,
                                size: fileData.size
                            });
                        },
                        (error) => reject(new Error(error))
                    );
                } else {
                    // 回退到Web实现
                    originalUpload.call(this, file).then(resolve).catch(reject);
                }
            });
        };
    }

    // 语音输入适配
    if (window.VoiceInputManager) {
        window.VoiceInputManager.startRecording = function() {
            if (window.AndroidInterface && window.AndroidInterface.startRecording) {
                return new Promise((resolve) => {
                    window.AndroidInterface.startRecording((text) => {
                        resolve(text);
                    });
                });
            }
            // 回退到Web实现
            return Promise.reject(new Error('Android录音功能不可用'));
        };

        window.VoiceInputManager.stopRecording = function() {
            if (window.AndroidInterface && window.AndroidInterface.stopRecording) {
                window.AndroidInterface.stopRecording();
            }
        };
    }

    // 存储适配
    const originalLocalStorage = {
        getItem: localStorage.getItem,
        setItem: localStorage.setItem,
        removeItem: localStorage.removeItem
    };

    // 重写localStorage以使用Android持久化存储
    if (window.AndroidInterface) {
        localStorage.getItem = function(key) {
            const androidValue = window.AndroidInterface.getStorage(key);
            return androidValue || originalLocalStorage.getItem.call(localStorage, key);
        };

        localStorage.setItem = function(key, value) {
            if (window.AndroidInterface.setStorage) {
                window.AndroidInterface.setStorage(key, value);
            }
            originalLocalStorage.setItem.call(localStorage, key, value);
        };

        localStorage.removeItem = function(key) {
            if (window.AndroidInterface.removeStorage) {
                window.AndroidInterface.removeStorage(key);
            }
            originalLocalStorage.removeItem.call(localStorage, key);
        };
    }

    // 设备信息注入
    if (window.AndroidInterface && window.AndroidInterface.getDeviceInfo) {
        try {
            const deviceInfo = JSON.parse(window.AndroidInterface.getDeviceInfo());
            window.DEVICE_INFO = deviceInfo;

            // 更新APP_CONFIG
            if (window.APP_CONFIG) {
                window.APP_CONFIG.deviceInfo = deviceInfo;
                window.APP_CONFIG.isAndroid = true;
            }
        } catch (error) {
            console.error('解析设备信息失败:', error);
        }
    }

    // Android返回键处理
    document.addEventListener('keydown', function(event) {
        if (event.key === 'Backspace' || event.keyCode === 8) {
            // Android返回键处理
            if (window.AndroidInterface && window.AndroidInterface.canGoBack) {
                const canGoBack = window.AndroidInterface.canGoBack();
                if (canGoBack) {
                    event.preventDefault();
                    window.AndroidInterface.goBack();
                }
            }
        }
    });

    // 页面可见性变化处理（Android后台/前台切换）
    document.addEventListener('visibilitychange', function() {
        if (document.visibilityState === 'visible') {
            // 页面回到前台，刷新会话列表等
            if (window.refreshConversations && typeof window.refreshConversations === 'function') {
                setTimeout(() => {
                    window.refreshConversations();
                }, 1000);
            }
        }
    });

    // 修复Android上的输入法问题
    const fixAndroidInput = function() {
        const inputs = document.querySelectorAll('input, textarea');
        inputs.forEach(input => {
            input.addEventListener('focus', function() {
                // 确保输入框在可视区域
                setTimeout(() => {
                    this.scrollIntoView({ behavior: 'smooth', block: 'center' });
                }, 300);
            });
        });
    };

    // DOM加载完成后执行
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', fixAndroidInput);
    } else {
        fixAndroidInput();
    }

    console.log('Android平台适配完成');
})();