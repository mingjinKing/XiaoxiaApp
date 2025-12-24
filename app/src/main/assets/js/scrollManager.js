// modules/scrollManager.js - 滚动管理

const {ref, nextTick} = Vue;
export const useScrollManager = () => {
    const messageContainer = ref(null);
    const shouldAutoScroll = ref(true);
    const isUserActivelyScrolling = ref(false);
    const isProgrammaticScrolling = ref(false);
    const lastScrollTop = ref(0);

    let userScrollTimeout = null;

    // 初始化滚动管理器
    const init = () => {
        messageContainer.value = document.querySelector('.message-container');
        if (messageContainer.value) {
            messageContainer.value.addEventListener('scroll', handleScroll);
        }
    };

    // 处理滚动事件 - 改进版本
    const handleScroll = () => {
        // 如果是程序触发的滚动，不处理
        if (isProgrammaticScrolling.value) return;

        const container = messageContainer.value;
        if (!container) return;

        const currentScrollTop = container.scrollTop;
        const scrollHeight = container.scrollHeight;
        const clientHeight = container.clientHeight;

        // 检测滚动方向
        const isScrollingUp = currentScrollTop < lastScrollTop.value;
        lastScrollTop.value = currentScrollTop;

        // 计算距离底部的距离
        const distanceFromBottom = scrollHeight - currentScrollTop - clientHeight;

        // 标记用户正在主动滚动
        isUserActivelyScrolling.value = true;

        // 清除之前的超时
        if (userScrollTimeout) {
            clearTimeout(userScrollTimeout);
        }

        // 设置超时，一段时间后认为用户停止滚动
        userScrollTimeout = setTimeout(() => {
            isUserActivelyScrolling.value = false;

            // 用户停止滚动后，如果距离底部很近，恢复自动滚动
            if (distanceFromBottom <= 50) {
                shouldAutoScroll.value = true;
            }
        }, 300); // 300ms后认为用户停止滚动

        // 如果用户向上滚动或距离底部较远，暂停自动滚动
        if (isScrollingUp || distanceFromBottom > 100) {
            shouldAutoScroll.value = false;
        }
    };

    // 程序触发的滚动
    const programmaticScrollToBottom = () => {
        if (!messageContainer.value) return;

        isProgrammaticScrolling.value = true;

        messageContainer.value.scrollTo({
            top: messageContainer.value.scrollHeight,
            behavior: 'smooth'
        });

        // 滚动完成后重置标志
        setTimeout(() => {
            isProgrammaticScrolling.value = false;
        }, 300); // 与滚动动画时间匹配
    };

    // 滚动到底部
    const scrollToBottom = () => {
        if (!shouldAutoScroll.value || isUserActivelyScrolling.value) return;

        nextTick(() => {
            programmaticScrollToBottom();
        });
    };

    // 强制滚动到底部（用于新消息发送时）
    const forceScrollToBottom = () => {
        shouldAutoScroll.value = true;
        isUserActivelyScrolling.value = false;
        programmaticScrollToBottom();
    };

    // 清理
    const cleanup = () => {
        if (messageContainer.value) {
            messageContainer.value.removeEventListener('scroll', handleScroll);
        }
        if (userScrollTimeout) {
            clearTimeout(userScrollTimeout);
        }
    };

    return {
        messageContainer,
        shouldAutoScroll,
        isUserActivelyScrolling,
        init,
        scrollToBottom,
        forceScrollToBottom,
        cleanup
    };
};