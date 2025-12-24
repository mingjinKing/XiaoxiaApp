// modules/fileUpload.js - 文件上传

const {ref} = Vue;
export const useFileUpload = (state) => {
    const isAttachmentMenuOpen = ref(false);
    const uploadedFiles = ref([]);

    // 切换附件菜单
    const toggleAttachmentMenu = () => {
        isAttachmentMenuOpen.value = !isAttachmentMenuOpen.value;
    };

    // 处理文件选择
    const handleFileSelect = (event) => {
        const files = Array.from(event.target.files);

        files.forEach(file => {
            if (validateFile(file)) {
                uploadedFiles.value.push({
                    id: generateFileId(),
                    name: file.name,
                    size: file.size,
                    type: file.type,
                    file: file
                });

                // 自动添加到输入消息中
                state.inputMessage += ` [附件: ${file.name}]`;
            }
        });

        // 重置input
        event.target.value = '';
        isAttachmentMenuOpen.value = false;
    };

    // 验证文件
    const validateFile = (file) => {
        const maxSize = 10 * 1024 * 1024; // 10MB
        const allowedTypes = [
            'image/jpeg', 'image/png', 'image/gif',
            'application/pdf',
            'text/plain',
            'application/msword',
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
        ];

        if (file.size > maxSize) {
            alert(`文件 ${file.name} 大小超过限制 (10MB)`);
            return false;
        }

        if (!allowedTypes.includes(file.type)) {
            alert(`不支持的文件类型: ${file.type}`);
            return false;
        }

        return true;
    };

    // 生成文件ID
    const generateFileId = () => {
        return Date.now().toString(36) + Math.random().toString(36).substr(2);
    };

    // 移除文件
    const removeFile = (fileId) => {
        const index = uploadedFiles.value.findIndex(file => file.id === fileId);
        if (index !== -1) {
            uploadedFiles.value.splice(index, 1);
        }
    };

    // 上传文件
    const uploadFiles = async () => {
        if (uploadedFiles.value.length === 0) return;

        const formData = new FormData();

        uploadedFiles.value.forEach(file => {
            formData.append('files', file.file);
        });

        formData.append('userId', state.userId);

        try {
            const response = await fetch('/xiaoXia/uploadFiles', {
                method: 'POST',
                body: formData
            });

            if (!response.ok) {
                throw new Error('文件上传失败');
            }

            const result = await response.json();
            console.log('文件上传成功:', result);

            // 清空已上传文件列表
            uploadedFiles.value = [];

        } catch (error) {
            console.error('文件上传错误:', error);
            alert('文件上传失败，请重试');
        }
    };

    return {
        isAttachmentMenuOpen,
        uploadedFiles,
        toggleAttachmentMenu,
        handleFileSelect,
        removeFile,
        uploadFiles
    };
};