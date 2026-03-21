import { useState, useEffect, useCallback } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import {
  ChevronDown, ChevronUp, FileStack, FileText, Loader2,
  RefreshCw, Sparkles,
} from 'lucide-react';
import { type SkillDTO } from '../api/skill';
import { interviewApi, type TextSessionMeta } from '../api/interview';
import { getSkillIcon } from '../utils/skillIcons';
import { getScoreTextColor } from '../utils/score';
import { formatDateTime } from '../utils/date';
import {
  useInterviewConfig,
  CUSTOM_SKILL_ID,
  DIFFICULTY_OPTIONS,
} from '../hooks/useInterviewConfig';

// 最近的面试记录项
interface RecentInterviewItem {
  id: string;
  title: string;
  status: string;
  evaluateStatus?: string | null;
  overallScore: number | null;
  createdAt: string;
}

export default function InterviewHubPage() {
  const navigate = useNavigate();

  const config = useInterviewConfig({ autoLoad: false });

  // === 最近面试记录 ===
  const [recentInterviews, setRecentInterviews] = useState<RecentInterviewItem[]>([]);
  const [loadingRecent, setLoadingRecent] = useState(false);

  const loadRecentInterviews = useCallback(async (allSkills: SkillDTO[]) => {
    setLoadingRecent(true);
    try {
      const textSessions = await interviewApi.listSessions().catch(() => [] as TextSessionMeta[]);

      const items: RecentInterviewItem[] = textSessions.map(s => ({
        id: s.sessionId,
        title: s.skillId || '未知方向',
        status: s.status,
        evaluateStatus: s.evaluateStatus,
        overallScore: s.overallScore,
        createdAt: s.createdAt,
      }));

      items.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
      setRecentInterviews(items.slice(0, 5));
    } catch (err) {
      console.error('Failed to load recent interviews:', err);
    } finally {
      setLoadingRecent(false);
    }
  }, []);

  // 初始加载：skills 和 resumes 并行，再用 skills 加载面试记录
  useEffect(() => {
    const init = async () => {
      const [skills] = await Promise.all([config.loadSkills(), config.loadResumes()]);
      await loadRecentInterviews(skills);
    };
    init();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleStart = () => {
    const selectedSkill = config.selectedSkill;
    const skillName = selectedSkill?.name || '自定义';

    if (config.isCustomStartDisabled) {
      return;
    }

    navigate('/interview', {
      state: {
        resumeId: config.resumeId,
        interviewConfig: {
          skillId: config.skillId,
          skillName,
          difficulty: config.difficulty,
          questionCount: config.questionCount,
          llmProvider: config.llmProvider,
          jdText: config.isCustomSkill ? config.parsedCustomJdText : undefined,
          customCategories: config.isCustomSkill ? config.customCategories : undefined,
        },
      },
    });
  };

  return (
    <div className="max-w-6xl mx-auto">
      {/* 页面标题 */}
      <motion.div 
        className="mb-10"
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <h1 className="text-3xl font-bold bg-gradient-to-r from-slate-800 via-primary-600 to-accent-600 dark:from-white dark:via-primary-400 dark:to-accent-400 bg-clip-text text-transparent flex items-center gap-3">
          <div className="w-12 h-12 bg-gradient-to-br from-primary-500 to-accent-500 rounded-xl flex items-center justify-center text-white shadow-lg shadow-primary-500/30">
            <Sparkles className="w-6 h-6" />
          </div>
          <span className="bg-clip-text">模拟面试</span>
        </h1>
        <p className="text-slate-500 dark:text-slate-400 mt-2 text-base">选择面试模式和方向，快速开始练习</p>
      </motion.div>

      {/* 配置区域 */}
      <motion.div 
        className="bg-white dark:bg-slate-800/95 backdrop-blur-xl rounded-2xl shadow-xl shadow-slate-200/30 dark:shadow-slate-900/30 border border-slate-200/50 dark:border-slate-700/50 p-8 mb-8"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.2 }}
      >
        <div className="space-y-6">
          {/* 面试方向 */}
            <div>
              <label className="flex items-center gap-2 mb-4 text-sm font-bold text-slate-700 dark:text-slate-200">
                <div className="w-1 h-5 bg-gradient-to-b from-primary-500 to-accent-500 rounded-full" />
                面试方向
              </label>
              {config.loadingSkills ? (
                <div className="flex items-center gap-3 py-6 px-4 bg-slate-50 dark:bg-slate-900/50 rounded-xl">
                  <Loader2 className="w-5 h-5 text-primary-500 animate-spin" />
                  <span className="text-sm text-slate-500 dark:text-slate-400">加载中...</span>
                </div>
              ) : (
                <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
                  {config.skills.map(skill => {
                    const selected = config.skillId === skill.id;
                    const IconComponent = getSkillIcon(skill.id);
                    const fallbackEmoji = skill.display?.icon || '📋';
                    return (
                      <motion.button
                        key={skill.id}
                        onClick={() => config.setSkillId(skill.id)}
                        whileHover={{ scale: 1.02 }}
                        whileTap={{ scale: 0.98 }}
                        className={`flex items-center gap-3 p-4 rounded-xl border-2 transition-all duration-300 text-left
                          ${selected
                            ? 'border-primary-500 bg-gradient-to-br from-primary-50 to-primary-100/50 dark:from-primary-900/30 dark:to-primary-900/20 shadow-lg shadow-primary-500/10'
                            : 'border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:border-slate-300 dark:hover:border-slate-600 hover:shadow-md'
                          }`}
                      >
                        <div className={`w-10 h-10 rounded-lg flex items-center justify-center text-base flex-shrink-0 transition-all duration-300 ${
                          selected ? skill.display?.iconBg || 'bg-primary-500 shadow-md shadow-primary-500/30' : 'bg-slate-100 dark:bg-slate-700'
                        }`}>
                          {IconComponent
                            ? <IconComponent className={`w-5 h-5 ${selected ? (skill.display?.iconColor || 'text-white') : 'text-slate-500 dark:text-slate-400'}`} />
                            : <span className={selected ? (skill.display?.iconColor || 'text-white') : ''}>{fallbackEmoji}</span>
                          }
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className={`text-sm font-semibold block truncate ${selected ? 'text-primary-700 dark:text-primary-300' : 'text-slate-700 dark:text-slate-300'}`}>
                            {skill.name}
                          </span>
                        </div>
                      </motion.button>
                    );
                  })}
                  {/* 自定义按钮 */}
                  <motion.button
                    onClick={() => config.setSkillId(CUSTOM_SKILL_ID)}
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                    className={`flex items-center gap-3 p-4 rounded-xl border-2 border-dashed transition-all duration-300 text-left
                      ${config.isCustomSkill
                        ? 'border-primary-500 bg-gradient-to-br from-primary-50 to-primary-100/50 dark:from-primary-900/30 dark:to-primary-900/20 shadow-lg shadow-primary-500/10'
                        : 'border-slate-300 dark:border-slate-600 hover:border-primary-400 dark:hover:border-primary-500 hover:shadow-md'
                      }`}
                  >
                    <div className={`w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 transition-all duration-300 ${
                      config.isCustomSkill ? 'bg-primary-500 shadow-md shadow-primary-500/30' : 'bg-slate-100 dark:bg-slate-700'
                    }`}>
                      {(() => {
                        const CustomIcon = getSkillIcon(CUSTOM_SKILL_ID);
                        return CustomIcon
                          ? <CustomIcon className={`w-5 h-5 ${config.isCustomSkill ? 'text-white' : 'text-slate-500 dark:text-slate-400'}`} />
                          : <span className="text-lg">✨</span>;
                      })()}
                    </div>
                    <span className={`text-sm font-semibold ${config.isCustomSkill ? 'text-primary-700 dark:text-primary-300' : 'text-slate-500 dark:text-slate-400'}`}>
                      自定义 JD
                    </span>
                  </motion.button>
                </div>
              )}
            </div>

          {/* 自定义 JD 输入 */}
          <AnimatePresence>
            {config.isCustomSkill && (
              <motion.div
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: 'auto', opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                className="overflow-hidden"
              >
                <div className="space-y-3 bg-slate-50 dark:bg-slate-900/50 rounded-xl p-4 border border-slate-200 dark:border-slate-700">
                  <textarea
                    value={config.customJdText}
                    onChange={e => config.setCustomJdText(e.target.value)}
                    placeholder="粘贴目标岗位的职位描述（JD），至少 50 字..."
                    rows={4}
                    className="w-full px-4 py-3 rounded-xl border border-slate-200 dark:border-slate-700
                      bg-white dark:bg-slate-800 text-sm text-slate-900 dark:text-white
                      placeholder:text-slate-400 resize-none focus:outline-none focus:ring-2
                      focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                  />
                  <button
                    onClick={config.handleParseJd}
                    disabled={config.parsingJd || !config.customJdText}
                    className="flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-lg
                      bg-primary-500 text-white hover:bg-primary-600 disabled:opacity-50
                      disabled:cursor-not-allowed transition-colors"
                  >
                    {config.parsingJd ? <Loader2 className="w-4 h-4 animate-spin" /> : <Sparkles className="w-4 h-4" />}
                    解析面试方向
                  </button>
                  {config.customCategories.length > 0 && (
                    <div className="flex flex-wrap gap-2">
                      {config.customCategories.map((cat, i) => (
                        <span
                          key={i}
                          className="px-3 py-1 text-xs font-medium rounded-full bg-primary-100 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300"
                        >
                          {cat.label}
                          <span className="ml-1 text-[10px] text-primary-500">({cat.priority})</span>
                        </span>
                      ))}
                    </div>
                  )}
                  {config.jdNeedsReparse && (
                    <p className="text-xs text-amber-600 dark:text-amber-400">
                      JD 已修改，请重新解析后再开始面试。
                    </p>
                  )}
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          {/* 难度 */}
          <div>
            <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
              难度
            </label>
            <div className="grid grid-cols-3 gap-3">
              {DIFFICULTY_OPTIONS.map(opt => {
                const selected = config.difficulty === opt.value;
                return (
                  <button
                    key={opt.value}
                    onClick={() => config.setDifficulty(opt.value)}
                    className={`py-3 px-4 rounded-xl border-2 transition-all duration-200 text-center
                      ${selected
                        ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-900/20'
                        : 'border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:border-slate-300 dark:hover:border-slate-600'
                      }`}
                  >
                    <p className={`text-sm font-semibold ${selected ? 'text-primary-700 dark:text-primary-300' : 'text-slate-700 dark:text-slate-300'}`}>
                      {opt.label}
                    </p>
                    <p className="text-xs text-slate-400">{opt.desc}</p>
                  </button>
                );
              })}
            </div>
          </div>

          {/* 更多选项 */}
          <button
            onClick={() => config.setShowMore(!config.showMore)}
            className="w-full flex items-center gap-2 py-2 text-sm text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300 transition-colors"
          >
            {config.showMore ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
            <span>更多选项</span>
            <div className="flex-1 border-t border-slate-200 dark:border-slate-700" />
          </button>

          <AnimatePresence>
            {config.showMore && (
              <motion.div
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: 'auto', opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                className="overflow-hidden space-y-4"
              >
                {/* 简历选择 */}
                <div className="bg-gradient-to-br from-primary-50/80 to-blue-50/80 dark:from-primary-900/20 dark:to-blue-900/10 rounded-xl p-4 border border-primary-100 dark:border-primary-800/30">
                  <div className="flex items-center gap-3 mb-3">
                    <FileStack className="w-5 h-5 text-primary-500" />
                    <p className="font-semibold text-sm text-primary-900 dark:text-primary-100">
                      基于简历面试（可选）
                    </p>
                  </div>
                  <select
                    value={config.resumeId || ''}
                    onChange={e => config.setResumeId(e.target.value ? parseInt(e.target.value) : undefined)}
                    className="w-full px-4 py-2.5 rounded-lg border border-primary-200 dark:border-primary-700/50
                      bg-white dark:bg-slate-800 text-sm text-slate-900 dark:text-white
                      focus:outline-none focus:ring-2 focus:ring-primary-500/50 transition-shadow"
                  >
                    <option value="">不使用简历（通用提问）</option>
                    {config.resumes.map(r => (
                      <option key={r.id} value={r.id}>{r.filename}</option>
                    ))}
                  </select>
                </div>

                {/* 文字面试 - 题目数 */}
                {config.mode === 'text' && (
                  <div>
                    <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
                      题目数量
                    </label>
                    <div className="flex gap-2">
                      {[6, 8, 10, 12].map(n => (
                        <button
                          key={n}
                          onClick={() => config.setQuestionCount(n)}
                          className={`flex-1 py-2 rounded-lg text-sm font-medium transition-all
                            ${config.questionCount === n
                              ? 'bg-primary-500 text-white shadow-sm'
                              : 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-600'
                            }`}
                        >
                          {n} 题
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* 开始面试按钮 */}
        <div className="mt-6 pt-6 border-t border-slate-100 dark:border-slate-700">
          <motion.button
            onClick={handleStart}
            whileHover={{ scale: 1.01 }}
            whileTap={{ scale: 0.99 }}
            disabled={config.isCustomStartDisabled}
            className="w-full px-6 py-3 rounded-xl font-semibold text-sm transition-all
              bg-gradient-to-r from-primary-500 to-primary-600 hover:from-primary-600 hover:to-primary-700
              text-white shadow-lg shadow-primary-500/25 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            开始面试
          </motion.button>
        </div>
      </motion.div>

      {/* 最近面试记录 */}
      <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-bold text-slate-800 dark:text-white">最近面试记录</h2>
          <Link
            to="/interviews"
            className="text-sm text-primary-500 hover:text-primary-600 font-medium transition-colors"
          >
            查看全部
          </Link>
        </div>

        {loadingRecent ? (
          <div className="flex items-center justify-center py-10">
            <Loader2 className="w-6 h-6 text-primary-500 animate-spin" />
          </div>
        ) : recentInterviews.length === 0 ? (
          <div className="text-center py-10">
            <p className="text-slate-400 dark:text-slate-500 text-sm">暂无面试记录，选择方向开始第一次面试吧</p>
          </div>
        ) : (
          <div className="space-y-2">
            {recentInterviews.map((item, index) => {
              const isCompleted = item.evaluateStatus === 'COMPLETED' || item.status === 'EVALUATED';
              const isEvaluating = item.evaluateStatus === 'PENDING' || item.evaluateStatus === 'PROCESSING';
              return (
                <motion.div
                  key={item.id}
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: index * 0.05 }}
                  onClick={() => navigate(`/interviews/${item.id}`)}
                  className="flex items-center gap-4 p-4 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors cursor-pointer group"
                >
                  {/* 类型图标 */}
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400">
                    <FileText className="w-5 h-5" />
                  </div>

                  {/* 信息 */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-sm text-slate-800 dark:text-white truncate">{item.title}</span>
                    </div>
                    <div className="flex items-center gap-3 mt-1">
                      <span className="text-xs text-slate-400 dark:text-slate-500">
                        {formatDateTime(item.createdAt)}
                      </span>
                      {isEvaluating && (
                        <span className="flex items-center gap-1 text-xs text-blue-500">
                          <RefreshCw className="w-3 h-3 animate-spin" /> 评估中
                        </span>
                      )}
                      {isCompleted && item.overallScore !== null && (
                        <span className="text-xs text-slate-600 dark:text-slate-300">
                          得分 <span className={`font-bold ${getScoreTextColor(item.overallScore!)}`}>{item.overallScore}</span>
                        </span>
                      )}
                    </div>
                  </div>

                  {/* 箭头 */}
                  <svg className="w-4 h-4 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-0.5 transition-all flex-shrink-0" viewBox="0 0 24 24" fill="none">
                    <polyline points="9,18 15,12 9,6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                  </svg>
                </motion.div>
              );
            })}
          </div>
        )}
      </div>

    </div>
  );
}
