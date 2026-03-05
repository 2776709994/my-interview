import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { historyApi } from '../api/history';
import { interviewApi, type TextSessionMeta } from '../api/interview';
import { formatDate } from '../utils/date';
import { getScoreProgressColor } from '../utils/score';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import {
  AlertCircle,
  CheckCircle,
  ChevronRight,
  Clock,
  Download,
  FileText,
  Loader2,
  PlayCircle,
  RefreshCw,
  Search,
  Trash2,
} from 'lucide-react';

interface UnifiedInterviewItem {
  id: string;
  title: string;
  sessionId: string;
  status: string;
  evaluateStatus?: string;
  evaluateError?: string;
  overallScore: number | null;
  totalQuestions?: number;
  createdAt: string;
  resumeId?: number;
}

function isCompletedStatus(status: string): boolean {
  return status === 'COMPLETED' || status === 'EVALUATED';
}

function isEvaluateCompleted(item: UnifiedInterviewItem): boolean {
  if (item.evaluateStatus === 'COMPLETED') return true;
  if (item.status === 'EVALUATED') return true;
  return false;
}

function isEvaluating(item: UnifiedInterviewItem): boolean {
  return item.evaluateStatus === 'PENDING' || item.evaluateStatus === 'PROCESSING';
}

function isEvaluateFailed(item: UnifiedInterviewItem): boolean {
  return item.evaluateStatus === 'FAILED';
}

function StatusIcon({ item }: { item: UnifiedInterviewItem }) {
  if (isEvaluateFailed(item)) return <AlertCircle className="w-4 h-4 text-red-500 dark:text-red-400"/>;
  if (isEvaluating(item)) return <RefreshCw className="w-4 h-4 text-blue-500 dark:text-blue-400 animate-spin"/>;
  if (isEvaluateCompleted(item)) return <CheckCircle className="w-4 h-4 text-green-500 dark:text-green-400"/>;
  if (item.status === 'IN_PROGRESS') return <PlayCircle className="w-4 h-4 text-blue-500 dark:text-blue-400"/>;
  return <Clock className="w-4 h-4 text-yellow-500 dark:text-yellow-400"/>;
}

function getStatusText(item: UnifiedInterviewItem): string {
  if (isEvaluateFailed(item)) return '评估失败';
  if (isEvaluating(item)) return item.evaluateStatus === 'PROCESSING' ? '评估中' : '等待评估';
  if (isEvaluateCompleted(item)) return '已完成';
  if (item.status === 'IN_PROGRESS') return '进行中';
  if (isCompletedStatus(item.status)) return '已提交';
  return '已创建';
}

export default function InterviewHistoryPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<UnifiedInterviewItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [deleteItem, setDeleteItem] = useState<UnifiedInterviewItem | null>(null);
  const [exporting, setExporting] = useState<string | null>(null);

  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const textSessions = await interviewApi.listSessions().catch(() => [] as TextSessionMeta[]);
      
      // 确保 textSessions 是数组
      const safeSessions = Array.isArray(textSessions) ? textSessions : [];
      
      const mappedItems: UnifiedInterviewItem[] = safeSessions.map(s => ({
        id: s.sessionId,
        title: s.skillId || '未知方向',
        sessionId: s.sessionId,
        status: s.status,
        evaluateStatus: s.evaluateStatus || undefined,
        evaluateError: s.evaluateError || undefined,
        overallScore: s.overallScore ?? null,
        totalQuestions: s.totalQuestions,
        createdAt: s.createdAt,
        resumeId: s.resumeId || undefined,
      }));

      mappedItems.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
      setItems(mappedItems);
    } catch (err) {
      console.error('Failed to load interviews:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  // 轮询：当有待处理项时，每5秒刷新一次
  useEffect(() => {
    const hasPendingItems = items.some(
      item => item.evaluateStatus === 'PENDING' || item.evaluateStatus === 'PROCESSING'
    );

    if (hasPendingItems && !loading) {
      const timer = setInterval(() => {
        loadAll();
      }, 5000);

      return () => clearInterval(timer);
    }
  }, [items, loading, loadAll]);

  const handleDelete = async () => {
    if (!deleteItem) return;
    
    try {
      console.log('🗑️ 开始删除会话:', deleteItem.sessionId);
      await interviewApi.deleteSession(deleteItem.sessionId);
      console.log('✅ 删除成功');
      setItems(prev => prev.filter(item => item.id !== deleteItem.id));
      setDeleteItem(null);
    } catch (err: any) {
      console.error('❌ 删除失败:', err);
      const errorMessage = err.response?.data?.message || err.message || '删除失败';
      alert(`删除失败: ${errorMessage}`);
    }
  };

  const handleExport = async (sessionId: string) => {
    setExporting(sessionId);
    try {
      const blob = await historyApi.exportInterviewPdf(sessionId);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `interview-report-${sessionId}.pdf`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err) {
      console.error('Failed to export:', err);
      alert('导出失败');
    } finally {
      setExporting(null);
    }
  };

  const filteredItems = items.filter(item => {
    if (searchTerm && !item.title.toLowerCase().includes(searchTerm.toLowerCase())) {
      return false;
    }
    return true;
  });

  return (
    <div className="max-w-5xl mx-auto">
      {/* 页面标题 */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
          <FileText className="w-7 h-7 text-primary-500" />
          面试记录
        </h1>
        <p className="text-slate-500 dark:text-slate-400 mt-1">查看和管理所有面试会话</p>
      </div>

      {/* 搜索框 */}
      <div className="mb-6">
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-slate-400" />
          <input
            type="text"
            placeholder="搜索面试记录..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pl-10 pr-4 py-3 rounded-xl border border-slate-200 dark:border-slate-700
              bg-white dark:bg-slate-800 text-sm text-slate-900 dark:text-white
              placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-primary-500/50"
          />
        </div>
      </div>

      {/* 面试列表 */}
      {loading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
        </div>
      ) : filteredItems.length === 0 ? (
        <div className="text-center py-20">
          <FileText className="w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
          <p className="text-slate-500 dark:text-slate-400">暂无面试记录</p>
        </div>
      ) : (
        <div className="space-y-3">
          {filteredItems.map((item, index) => (
            <motion.div
              key={item.id}
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: index * 0.05 }}
              onClick={() => navigate(`/interviews/${item.sessionId}`)}
              className="bg-white dark:bg-slate-800 rounded-xl p-5 shadow-sm border border-slate-100 dark:border-slate-700
                hover:shadow-md hover:border-primary-200 dark:hover:border-primary-800 transition-all cursor-pointer group"
            >
              <div className="flex items-center gap-4">
                {/* 状态图标 */}
                <div className="w-12 h-12 rounded-xl flex items-center justify-center flex-shrink-0 bg-blue-100 dark:bg-blue-900/30">
                  <StatusIcon item={item} />
                </div>

                {/* 信息 */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="font-semibold text-slate-800 dark:text-white truncate">{item.title}</span>
                    <span className="px-2 py-0.5 rounded text-[10px] font-medium bg-blue-50 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400">
                      文字面试
                    </span>
                  </div>
                  <div className="flex items-center gap-3 text-xs text-slate-500 dark:text-slate-400">
                    <span>{formatDate(item.createdAt)}</span>
                    {item.totalQuestions && <span>{item.totalQuestions} 题</span>}
                    {isEvaluating(item) && (
                      <span className="flex items-center gap-1 text-blue-500">
                        <RefreshCw className="w-3 h-3 animate-spin" /> {getStatusText(item)}
                      </span>
                    )}
                    {isEvaluateCompleted(item) && item.overallScore !== null && (
                      <span className="text-slate-600 dark:text-slate-300">
                        得分 <span className={`font-bold ${getScoreProgressColor(item.overallScore!)}`}>{item.overallScore}</span>
                      </span>
                    )}
                  </div>
                </div>

                {/* 操作按钮 */}
                <div className="flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
                  {isEvaluateCompleted(item) && (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleExport(item.sessionId);
                      }}
                      disabled={exporting === item.sessionId}
                      className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                      title="导出报告"
                    >
                      {exporting === item.sessionId ? (
                        <Loader2 className="w-4 h-4 text-slate-500 animate-spin" />
                      ) : (
                        <Download className="w-4 h-4 text-slate-500" />
                      )}
                    </button>
                  )}
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setDeleteItem(item);
                    }}
                    className="p-2 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
                    title="删除"
                  >
                    <Trash2 className="w-4 h-4 text-red-500" />
                  </button>
                  <ChevronRight className="w-5 h-5 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 transition-colors" />
                </div>
              </div>
            </motion.div>
          ))}
        </div>
      )}

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={!!deleteItem}
        item={deleteItem}
        itemType="面试记录"
        onConfirm={handleDelete}
        onCancel={() => setDeleteItem(null)}
      />
    </div>
  );
}
