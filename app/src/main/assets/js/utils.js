// modules/utils.js - 工具函数
export const utils = {
    // 渲染Markdown
    renderMarkdown: (content) => {
        return marked.parse(content);
    },

    // 渲染图表
    renderChart: (chartId, option) => {
        const chartDom = document.getElementById(chartId);
        if (!chartDom) return;

        const chart = echarts.init(chartDom);
        chart.setOption(option);

        // 响应窗口大小变化
        window.addEventListener('resize', () => {
            chart.resize();
        });
    },

    // 格式化文件大小
    formatFileSize: (bytes) => {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    },

    // 防抖函数
    debounce: (func, wait) => {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    },

    // 生成唯一ID
    generateId: () => {
        return Date.now().toString(36) + Math.random().toString(36).substr(2);
    },

    // 格式化时间
    formatTime: (timestamp) => {
        if (!timestamp) return '';

        const date = new Date(timestamp);
        const now = new Date();

        // 重置时间为0点，用于日期比较
        const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
        const yesterday = new Date(today);
        yesterday.setDate(yesterday.getDate() - 1);

        const targetDate = new Date(date.getFullYear(), date.getMonth(), date.getDate());

        // 如果是今天
        if (targetDate.getTime() === today.getTime()) {
            return date.toLocaleTimeString('zh-CN', {
                hour: '2-digit',
                minute: '2-digit'
            });
        }

        // 如果是昨天
        if (targetDate.getTime() === yesterday.getTime()) {
            return '昨天 ' + date.toLocaleTimeString('zh-CN', {
                hour: '2-digit',
                minute: '2-digit'
            });
        }

        // 如果是今年
        if (date.getFullYear() === now.getFullYear()) {
            return date.toLocaleDateString('zh-CN', {
                month: '2-digit',
                day: '2-digit'
            }) + ' ' + date.toLocaleTimeString('zh-CN', {
                hour: '2-digit',
                minute: '2-digit'
            });
        }

        // 其他情况显示完整日期和时间
        return date.toLocaleDateString('zh-CN') + ' ' + date.toLocaleTimeString('zh-CN', {
            hour: '2-digit',
            minute: '2-digit'
        });
    }
};