import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {motion} from 'framer-motion';
import {Database, FileStack, MessageSquare, Moon, Settings, Sparkles, Sun, Users,} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {useState} from 'react';
import UnifiedInterviewModal, {UnifiedInterviewConfig} from './UnifiedInterviewModal';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();
  const navigate = useNavigate();
  const [interviewModalPreset, setInterviewModalPreset] = useState<{
    defaultMode: 'text' | 'voice';
    defaultResumeId?: number;
    title: string;
    subtitle: string;
    startButtonText: string;
  } | null>(null);

  const openInterviewModalWithResume = (resumeId: number) => {
    setInterviewModalPreset({
      defaultMode: 'text',
      defaultResumeId: resumeId,
      title: '开始模拟面试',
      subtitle: '配置面试参数，开始练习',
      startButtonText: '开始面试',
    });
  };

  const handleInterviewStart = (config: UnifiedInterviewConfig) => {
    setInterviewModalPreset(null);
    if (config.mode === 'text') {
      navigate('/interview', {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
            llmProvider: config.llmProvider,
          },
        },
      });
    }
  };

  // 按业务模块组织的导航项
  const navGroups: NavGroup[] = [
    {
      id: 'interview',
      title: '面试准备',
      items: [
        { id: 'resumes', path: '/history', label: '简历管理', icon: FileStack, description: '管理简历，AI 分析' },
        { id: 'interview-hub', path: '/interview-hub', label: '模拟面试', icon: Sparkles, description: '文字面试练习' },
        { id: 'interviews', path: '/interviews', label: '面试记录', icon: Users, description: '查看面试历史' },
      ],
    },
    {
      id: 'knowledge',
      title: '知识库',
      items: [
        { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database, description: '管理知识文档' },
        { id: 'chat', path: '/knowledgebase/chat', label: '问答助手', icon: MessageSquare, description: '基于知识库问答' },
      ],
    },
    {
      id: 'system',
      title: '系统',
      items: [
        { id: 'settings', path: '/settings', label: '系统设置', icon: Settings, description: '模型服务配置' },
      ],
    },
  ];

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path.startsWith('#')) return false;
    if (path === '/history') {
      return currentPath === '/history'
        || currentPath === '/'
        || currentPath.startsWith('/history/')
        || currentPath === '/upload';
    }
    if (path === '/interview-hub') {
      return currentPath === '/interview-hub'
        || currentPath === '/interview'
        || currentPath.startsWith('/interview/');
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    return currentPath.startsWith(path);
  };

  return (
    <div className="flex min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800">
      {/* 左侧边栏 */}
      <aside className="w-72 bg-white/80 dark:bg-slate-900/80 backdrop-blur-xl border-r border-slate-200/50 dark:border-slate-700/50 fixed h-screen left-0 top-0 z-50 flex flex-col shadow-xl shadow-slate-200/20 dark:shadow-slate-900/30">
        {/* Logo */}
        <div className="p-6 border-b border-slate-100 dark:border-slate-700/50 flex items-center justify-between">
          <Link to="/history" className="flex items-center gap-3 group">
            <div className="w-11 h-11 bg-gradient-to-br from-primary-500 via-primary-600 to-accent-500 rounded-xl flex items-center justify-center text-white shadow-lg shadow-primary-500/40 group-hover:shadow-primary-500/60 transition-all duration-300 group-hover:scale-105">
              <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/>
              </svg>
            </div>
            <div>
              <span className="text-lg font-bold bg-gradient-to-r from-slate-800 to-slate-600 dark:from-white dark:to-slate-200 bg-clip-text text-transparent tracking-tight block">
                TalentPilot
              </span>
            </div>
          </Link>
        </div>

        {/* 主题切换按钮 */}
        <div className="px-4 py-3">
          <button
            onClick={toggleTheme}
            className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-gradient-to-r from-slate-50 to-slate-100/50 dark:from-slate-800 dark:to-slate-800/50 text-slate-600 dark:text-slate-300 hover:from-slate-100 dark:hover:from-slate-700 hover:to-slate-100/50 dark:hover:to-slate-700/50 transition-all duration-300 border border-slate-200/50 dark:border-slate-700/50 hover:border-primary-300 dark:hover:border-primary-600 hover:shadow-md"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="w-4 h-4" />
                <span className="text-sm font-medium">浅色模式</span>
              </>
            ) : (
              <>
                <Moon className="w-4 h-4" />
                <span className="text-sm font-medium">深色模式</span>
              </>
            )}
          </button>
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 p-4 overflow-y-auto">
          <div className="space-y-6">
            {navGroups.map((group) => (
              <div key={group.id}>
                <div className="px-3 mb-3">
                  <span className="text-xs font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest">
                    {group.title}
                  </span>
                </div>
                <div className="space-y-1.5">
                  {group.items.map((item) => {
                    const active = isActive(item.path);

                    return (
                      <Link
                        key={item.id}
                        to={item.path}
                        className={`group relative flex items-center gap-3 px-3.5 py-3 rounded-xl transition-all duration-300
                          ${active
                            ? 'bg-gradient-to-r from-primary-50 to-primary-100/50 dark:from-primary-900/40 dark:to-primary-900/20 text-primary-600 dark:text-primary-400 shadow-md shadow-primary-500/10 dark:shadow-primary-500/5'
                            : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800/50 hover:text-slate-900 dark:hover:text-white hover:shadow-sm'
                          }`}
                      >
                        <div className={`w-10 h-10 rounded-lg flex items-center justify-center transition-all duration-300
                          ${active
                            ? 'bg-primary-500 dark:bg-primary-600 text-white shadow-lg shadow-primary-500/30'
                            : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 group-hover:bg-slate-200 dark:group-hover:bg-slate-700 group-hover:text-slate-700 dark:group-hover:text-white'
                          }`}
                        >
                          <item.icon className="w-5 h-5" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className={`text-sm block ${active ? 'font-bold' : 'font-medium'}`}>
                            {item.label}
                          </span>
                          {item.description && (
                            <span className={`text-xs truncate block mt-0.5 ${active ? 'text-primary-500 dark:text-primary-400' : 'text-slate-400 dark:text-slate-500'}`}>
                              {item.description}
                            </span>
                          )}
                        </div>
                        {active && (
                          <div className="absolute right-2 top-1/2 -translate-y-1/2">
                            <div className="w-1.5 h-1.5 rounded-full bg-primary-500 dark:bg-primary-400 animate-pulse" />
                          </div>
                        )}
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </nav>

        {/* 底部信息 */}
        <div className="p-4 border-t border-slate-100 dark:border-slate-700/50">
          <div className="px-4 py-3 bg-gradient-to-r from-primary-50 via-accent-50/50 to-primary-50 dark:from-primary-900/30 dark:via-accent-900/20 dark:to-slate-800/50 rounded-xl border border-primary-100/50 dark:border-primary-800/30">
            <p className="text-xs text-primary-600 dark:text-primary-400 font-bold">TalentPilot v1.0</p>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">Powered by AI · 智能人才评估</p>
          </div>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className="flex-1 ml-72 p-8 lg:p-12 min-h-screen overflow-y-auto">
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -20 }}
          transition={{ duration: 0.4, ease: "easeOut" }}
          className="max-w-7xl mx-auto"
        >
          <Outlet context={{ openInterviewModalWithResume }} />
        </motion.div>
      </main>

      {/* 统一面试弹窗 */}
      <UnifiedInterviewModal
        isOpen={interviewModalPreset !== null}
        onClose={() => setInterviewModalPreset(null)}
        onStart={handleInterviewStart}
        defaultMode={interviewModalPreset?.defaultMode || 'text'}
        defaultResumeId={interviewModalPreset?.defaultResumeId}
        hideModeSwitch={interviewModalPreset?.defaultResumeId == null}
        title={interviewModalPreset?.title || '开始模拟面试'}
        subtitle={interviewModalPreset?.subtitle || '选择面试模式和主题，快速开始'}
        startButtonText={interviewModalPreset?.startButtonText || '开始面试'}
      />
    </div>
  );
}
