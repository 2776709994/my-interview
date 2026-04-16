import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {AnimatePresence, motion} from 'framer-motion';
import {AlertCircle, CheckCircle, ChevronLeft, ChevronRight, Clock, FileStack, RefreshCw, Sparkles, Upload} from 'lucide-react';
import {historyApi, PaginatedResumeList, ResumeListItem} from '../api/history';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import {formatDateOnly} from '../utils/date';
import {getScoreProgressColor} from '../utils/score';
import { ROUTES } from '../constants/routes';

interface HistoryListProps {
  onSelectResume: (id: number) => void;
}

function isAnalyzing(status?: string): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

function AnalyzeStatusIcon({status}: { status?: string }) {
  if (status === 'FAILED') return <AlertCircle className="w-4 h-4 text-red-500 dark:text-red-400"/>;
  if (isAnalyzing(status)) return <RefreshCw className="w-4 h-4 text-blue-500 dark:text-blue-400 animate-spin"/>;
  if (status === 'COMPLETED') return <CheckCircle className="w-4 h-4 text-green-500 dark:text-green-400"/>;
  return <Clock className="w-4 h-4 text-yellow-500 dark:text-yellow-400"/>;
}

function getAnalyzeStatusText(status?: string): string {
  if (status === 'FAILED') return '分析失败';
  if (status === 'PROCESSING') return '分析中';
  if (status === 'PENDING') return '等待分析';
  if (status === 'COMPLETED') return '分析完成';
  return '待分析';
}

export default function HistoryList({onSelectResume}: HistoryListProps) {
  const navigate = useNavigate();
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ id: number; filename: string } | null>(null);
  
  // 分页状态
  const [pagination, setPagination] = useState({
    page: 1,
    total: 0,
    totalPages: 0,
    size: 10,
  });

  const loadResumes = useCallback(async (isPolling = false) => {
    if (!isPolling) setLoading(true);
    try {
      const data: PaginatedResumeList = await historyApi.getResumes(pagination.page);
      setResumes(data.records);
      setPagination({
        page: data.current,
        total: data.total,
        totalPages: data.pages,
        size: data.size,
      });
    } catch (err) {
      console.error('加载历史记录失败', err);
    } finally {
      if (!isPolling) setLoading(false);
    }
  }, [pagination.page]);

  useEffect(() => {
    loadResumes();
  }, [loadResumes]);

  // 轮询：有分析中的简历时启动 3s 轮询
  const hasAnalyzing = resumes.some(r => isAnalyzing(r.analyzeStatus));

  useEffect(() => {
    if (!hasAnalyzing) return;
    const id = window.setInterval(() => loadResumes(true), 3000);
    return () => clearInterval(id);
  }, [hasAnalyzing, loadResumes]);

  // 切换页码
  const handlePageChange = (newPage: number) => {
    if (newPage < 1 || newPage > pagination.totalPages) return;
    setPagination(prev => ({ ...prev, page: newPage }));
  };

  const handleDeleteClick = (id: number, filename: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteConfirm({id, filename});
  };

  const handleDeleteConfirm = async () => {
    if (!deleteConfirm) return;

    const {id} = deleteConfirm;
    setDeletingId(id);
    try {
      await historyApi.deleteResume(id);
      await loadResumes();
      setDeleteConfirm(null);
    } catch (err) {
      alert(err instanceof Error ? err.message : '删除失败，请稍后重试');
    } finally {
      setDeletingId(null);
    }
  };

  const filteredResumes = resumes.filter(resume =>
    resume.filename.toLowerCase().includes(searchTerm.toLowerCase())
  );

  return (
    <motion.div
      className="w-full"
      initial={{opacity: 0}}
      animate={{opacity: 1}}
    >
      {/* 头部 */}
      <div className="flex items-center justify-between mb-8">
        <div>
          <motion.h1 
            className="text-3xl font-bold bg-gradient-to-r from-slate-800 via-primary-600 to-accent-600 dark:from-white dark:via-primary-400 dark:to-accent-400 bg-clip-text text-transparent flex items-center gap-3"
            initial={{ opacity: 0, x: -20 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.5 }}
          >
            <div className="w-12 h-12 bg-gradient-to-br from-primary-500 to-accent-500 rounded-xl flex items-center justify-center text-white shadow-lg shadow-primary-500/30">
              <FileStack className="w-6 h-6" />
            </div>
            <span className="bg-clip-text">简历管理</span>
          </motion.h1>
          <motion.p 
            className="text-slate-500 dark:text-slate-400 mt-2 text-base"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.2 }}
          >
            管理您的简历，AI 智能分析与评分
          </motion.p>
        </div>
        <motion.div 
          className="flex gap-3"
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ delay: 0.3 }}
        >
          <button
            onClick={() => navigate(ROUTES.resumeUpload)}
            className="flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-primary-500 to-primary-600 text-white rounded-xl hover:from-primary-600 hover:to-primary-700 transition-all duration-300 shadow-lg shadow-primary-500/25 hover:shadow-primary-500/40 hover:scale-105 active:scale-95 font-medium"
          >
            <Upload className="w-4 h-4" />
            上传简历
          </button>
          <button
            onClick={() => navigate('/interview-hub')}
            className="flex items-center gap-2 px-5 py-2.5 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-700 transition-all duration-300 border border-slate-200 dark:border-slate-700 hover:border-primary-300 dark:hover:border-primary-600 hover:shadow-md active:scale-95 font-medium"
          >
            <Sparkles className="w-4 h-4" />
            模拟面试
          </button>
        </motion.div>
      </div>

      {/* 搜索栏 */}
      <motion.div 
        className="mb-6"
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.4 }}
      >
        <div className="flex items-center gap-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl px-5 py-3.5 max-w-md focus-within:border-primary-500 focus-within:ring-4 focus-within:ring-primary-500/10 dark:focus-within:ring-primary-500/20 transition-all duration-300 shadow-sm hover:shadow-md">
          <svg className="w-5 h-5 text-slate-400" viewBox="0 0 24 24" fill="none">
            <circle cx="11" cy="11" r="8" stroke="currentColor" strokeWidth="2"/>
            <line x1="21" y1="21" x2="16.65" y2="16.65" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
          </svg>
          <input
            type="text"
            placeholder="搜索简历..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="flex-1 outline-none text-slate-700 dark:text-slate-200 placeholder:text-slate-400 bg-transparent"
          />
        </div>
      </motion.div>

      {/* 加载状态 */}
      {loading && (
        <div className="text-center py-20">
          <motion.div
            className="w-10 h-10 border-3 border-slate-200 dark:text-slate-200 border-t-primary-500 rounded-full mx-auto mb-4"
            animate={{rotate: 360}}
            transition={{duration: 1, repeat: Infinity, ease: "linear"}}
          />
          <p className="text-slate-500 dark:text-slate-400">加载中...</p>
        </div>
      )}

      {/* 空状态 */}
      {!loading && filteredResumes.length === 0 && (
        <motion.div
          className="text-center py-20 bg-white dark:bg-slate-800 rounded-2xl"
          initial={{opacity: 0, scale: 0.95}}
          animate={{opacity: 1, scale: 1}}
        >
          <div className="text-6xl mb-6">📄</div>
          <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">暂无简历记录</h3>
          <p className="text-slate-500 dark:text-slate-400">上传简历开始您的第一次 AI 面试分析</p>
        </motion.div>
      )}

      {/* 表格 */}
      {!loading && filteredResumes.length > 0 && (
        <motion.div
          className="bg-white dark:bg-slate-800/95 backdrop-blur-xl rounded-2xl shadow-xl shadow-slate-200/30 dark:shadow-slate-900/30 overflow-hidden border border-slate-200/50 dark:border-slate-700/50"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
        >
          <table className="w-full">
            <thead>
            <tr className="bg-gradient-to-r from-slate-50 to-slate-100/50 dark:from-slate-800 dark:to-slate-700/50 border-b border-slate-200 dark:border-slate-700">
              <th className="text-left px-6 py-4 text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider">简历名称</th>
              <th className="text-left px-6 py-4 text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider">上传日期</th>
              <th className="text-left px-6 py-4 text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider">分析状态</th>
              <th className="text-left px-6 py-4 text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider">AI 评分</th>
              <th className="text-left px-6 py-4 text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider">面试状态</th>
              <th className="w-20"></th>
            </tr>
            </thead>
            <tbody>
            <AnimatePresence>
              {filteredResumes.map((resume, index) => (
                <motion.tr
                  key={resume.id}
                  initial={{ opacity: 0, x: -20 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: index * 0.05 }}
                  onClick={() => onSelectResume(resume.id)}
                  className="border-b border-slate-100 dark:border-slate-700/50 last:border-0 hover:bg-gradient-to-r hover:from-primary-50/50 hover:to-transparent dark:hover:from-primary-900/10 dark:hover:to-transparent cursor-pointer transition-all duration-300 group"
                >
                  <td className="px-6 py-5">
                    <div className="flex items-center gap-4">
                      <div
                        className="w-11 h-11 bg-gradient-to-br from-primary-100 to-primary-50 dark:from-primary-900/40 dark:to-primary-900/20 rounded-xl flex items-center justify-center text-primary-600 dark:text-primary-400 shadow-sm group-hover:shadow-md transition-all duration-300"
                      >
                        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
                          <path d="M14 2H6C5.46957 2 4.96086 2.21071 4.58579 2.58579C4.21071 2.96086 4 3.46957 4 4V20C4 20.5304 4.21071 21.0391 4.58579 21.4142C4.96086 21.7893 5.46957 22 6 22H18C18.5304 22 19.0391 21.7893 19.4142 21.4142C19.7893 21.0391 20 20.5304 20 20V8L14 2Z"
                                stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                                strokeLinejoin="round"/>
                          <polyline points="14,2 14,8 20,8" stroke="currentColor" strokeWidth="2"
                                    strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                      </div>
                      <span className="font-semibold text-slate-800 dark:text-white group-hover:text-primary-600 dark:group-hover:text-primary-400 transition-colors">{resume.filename}</span>
                    </div>
                  </td>
                  <td className="px-6 py-5 text-slate-500 dark:text-slate-400">{formatDateOnly(resume.uploadedAt)}</td>
                  <td className="px-6 py-5">
                    <div className="flex items-center gap-2">
                      <AnalyzeStatusIcon status={resume.analyzeStatus}/>
                      <span className="text-sm font-medium text-slate-600 dark:text-slate-300">
                        {getAnalyzeStatusText(resume.analyzeStatus)}
                      </span>
                    </div>
                  </td>
                  <td className="px-6 py-5">
                    {resume.analyzeStatus === 'COMPLETED' && resume.latestScore !== undefined ? (
                      <div className="flex items-center gap-3">
                        <div
                          className="w-24 h-2.5 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden shadow-inner">
                          <motion.div
                            className={`h-full ${getScoreProgressColor(resume.latestScore)} rounded-full shadow-sm`}
                            initial={{ width: 0 }}
                            animate={{ width: `${resume.latestScore}%`}}
                            transition={{ duration: 1, delay: index * 0.1, ease: "easeOut" }}
                          />
                        </div>
                        <span className="font-bold text-slate-800 dark:text-white text-base">{resume.latestScore}</span>
                      </div>
                    ) : isAnalyzing(resume.analyzeStatus) ? (
                      <span className="text-blue-500 dark:text-blue-400 text-sm font-medium">生成中...</span>
                    ) : resume.analyzeStatus === 'FAILED' ? (
                      <span className="text-red-500 dark:text-red-400 text-sm font-medium"
                            title={resume.analyzeError}>失败</span>
                    ) : (
                      <span className="text-slate-400 dark:text-slate-500">-</span>
                    )}
                  </td>
                  <td className="px-6 py-5">
                    {resume.interviewCount > 0 ? (
                      <span
                        className="inline-flex items-center gap-1.5 px-3.5 py-2 bg-gradient-to-r from-emerald-50 to-emerald-100/50 dark:from-emerald-900/30 dark:to-emerald-900/20 text-emerald-700 dark:text-emerald-400 rounded-full text-sm font-semibold shadow-sm border border-emerald-200/50 dark:border-emerald-800/30">
                        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none">
                          <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2"/>
                          <polyline points="9,12 11,14 15,10" stroke="currentColor" strokeWidth="2"
                                    strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                        已完成
                      </span>
                    ) : (
                      <span
                        className="inline-flex px-3.5 py-2 bg-slate-100 dark:bg-slate-700/50 text-slate-500 dark:text-slate-400 rounded-full text-sm font-medium border border-slate-200/50 dark:border-slate-600/50">待面试</span>
                    )}
                  </td>
                  <td className="px-4">
                    <div className="flex items-center gap-2">
                      <button
                        onClick={(e) => handleDeleteClick(resume.id, resume.filename, e)}
                        disabled={deletingId === resume.id}
                        className="p-2.5 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-lg transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed hover:scale-110"
                        title="删除简历"
                      >
                        {deletingId === resume.id ? (
                          <motion.div
                            className="w-5 h-5 border-2 border-red-500 border-t-transparent rounded-full"
                            animate={{rotate: 360}}
                            transition={{duration: 1, repeat: Infinity, ease: "linear"}}
                          />
                        ) : (
                          <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
                            <path d="M3 6H5H21M8 6V4C8 3.46957 8.21071 2.96086 8.58579 2.58579C8.96086 2.21071 9.46957 2 10 2H14C14.5304 2 15.0391 2.21071 15.4142 2.58579C15.7893 2.96086 16 3.46957 16 4V6M19 6V20C19 20.5304 18.7893 21.0391 18.4142 21.4142C18.0391 21.7893 17.5304 22 17 22H7C6.46957 22 5.96086 21.7893 5.58579 21.4142C5.21071 21.0391 5 20.5304 5 20V6H19Z"
                                  stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                                  strokeLinejoin="round"/>
                            <path d="M10 11V17M14 11V17" stroke="currentColor" strokeWidth="2"
                                  strokeLinecap="round" strokeLinejoin="round"/>
                          </svg>
                        )}
                      </button>
                      <svg
                        className="w-5 h-5 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-1 transition-all duration-300"
                        viewBox="0 0 24 24" fill="none">
                        <polyline points="9,18 15,12 9,6" stroke="currentColor" strokeWidth="2"
                                  strokeLinecap="round" strokeLinejoin="round"/>
                      </svg>
                    </div>
                  </td>
                </motion.tr>
              ))}
            </AnimatePresence>
            </tbody>
          </table>
        </motion.div>
      )}

      {/* 分页组件 */}
      {!loading && filteredResumes.length > 0 && (
        <motion.div
          className="flex items-center justify-between mt-8 px-4 py-4 bg-white dark:bg-slate-800/95 backdrop-blur-xl rounded-2xl shadow-lg shadow-slate-200/20 dark:shadow-slate-900/20 border border-slate-200/50 dark:border-slate-700/50"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.3 }}
        >
          <div className="text-sm text-slate-600 dark:text-slate-400">
            共 <span className="font-bold text-primary-600 dark:text-primary-400">{pagination.total}</span> 条记录，
            第 <span className="font-bold text-primary-600 dark:text-primary-400">{pagination.page}</span> / {pagination.totalPages} 页
          </div>
          
          <div className="flex items-center gap-2">
            {/* 上一页按钮 */}
            <button
              onClick={() => handlePageChange(pagination.page - 1)}
              disabled={pagination.page === 1}
              className="flex items-center gap-1.5 px-4 py-2.5 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-40 disabled:cursor-not-allowed transition-all duration-300 hover:border-primary-300 dark:hover:border-primary-600 hover:shadow-md font-medium"
            >
              <ChevronLeft className="w-4 h-4" />
              上一页
            </button>

            {/* 页码按钮 */}
            <div className="flex items-center gap-1.5">
              {Array.from({ length: Math.min(5, pagination.totalPages) }, (_, i) => {
                let pageNum: number;
                if (pagination.totalPages <= 5) {
                  pageNum = i + 1;
                } else if (pagination.page <= 3) {
                  pageNum = i + 1;
                } else if (pagination.page >= pagination.totalPages - 2) {
                  pageNum = pagination.totalPages - 4 + i;
                } else {
                  pageNum = pagination.page - 2 + i;
                }

                return (
                  <button
                    key={pageNum}
                    onClick={() => handlePageChange(pageNum)}
                    className={`w-10 h-10 rounded-xl text-sm font-bold transition-all duration-300 ${
                      pagination.page === pageNum
                        ? 'bg-gradient-to-r from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/30 scale-105'
                        : 'bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 hover:border-primary-300 dark:hover:border-primary-600 hover:shadow-md'
                    }`}
                  >
                    {pageNum}
                  </button>
                );
              })}
            </div>

            {/* 下一页按钮 */}
            <button
              onClick={() => handlePageChange(pagination.page + 1)}
              disabled={pagination.page === pagination.totalPages}
              className="flex items-center gap-1.5 px-4 py-2.5 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-40 disabled:cursor-not-allowed transition-all duration-300 hover:border-primary-300 dark:hover:border-primary-600 hover:shadow-md font-medium"
            >
              下一页
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        </motion.div>
      )}

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={deleteConfirm !== null}
        item={deleteConfirm}
        itemType="简历"
        loading={deletingId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteConfirm(null)}
        customMessage={
          deleteConfirm ? (
            <>
              <p className="mb-2">确定要删除简历 <strong>"{deleteConfirm.filename}"</strong> 吗？</p>
              <p className="text-sm text-slate-500 dark:text-slate-400 mb-2">删除后将同时删除：</p>
              <ul className="text-sm text-slate-500 dark:text-red-400 list-disc list-inside mb-2">
                <li>简历评价记录</li>
                <li>所有模拟面试记录</li>
              </ul>
              <p className="text-sm font-semibold text-red-600">此操作不可恢复！</p>
            </>
          ) : undefined
        }
      />
    </motion.div>
  );
}
