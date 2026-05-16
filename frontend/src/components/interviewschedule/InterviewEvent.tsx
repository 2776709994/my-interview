// frontend/src/components/interviewschedule/InterviewEvent.tsx

import React from 'react';
import { motion } from 'framer-motion';
import dayjs from 'dayjs';
import type { InterviewSchedule, InterviewStatus } from '../../types/interviewSchedule';

interface InterviewEventProps {
  event: InterviewSchedule;
}

const statusLabels: Record<InterviewStatus, string> = {
  PENDING: '待面试',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  RESCHEDULED: '已改期',
};

export const InterviewEvent: React.FC<InterviewEventProps> = ({ event }) => {
  const statusConfig = {
    PENDING: {
      bg: 'bg-blue-100/90 dark:bg-blue-500/25',
      text: 'text-blue-900 dark:text-blue-100',
      border: 'border-blue-300/60 dark:border-blue-400/40',
      shadow: 'shadow-blue-200/60 dark:shadow-blue-500/20',
    },
    COMPLETED: {
      bg: 'bg-emerald-100/90 dark:bg-emerald-500/25',
      text: 'text-emerald-900 dark:text-emerald-100',
      border: 'border-emerald-300/60 dark:border-emerald-400/40',
      shadow: 'shadow-emerald-200/60 dark:shadow-emerald-500/20',
    },
    CANCELLED: {
      bg: 'bg-slate-100/90 dark:bg-slate-500/25',
      text: 'text-slate-700 dark:text-slate-200',
      border: 'border-slate-300/60 dark:border-slate-400/40',
      shadow: 'shadow-slate-200/60 dark:shadow-slate-500/20',
    },
    RESCHEDULED: {
      bg: 'bg-amber-100/90 dark:bg-amber-500/25',
      text: 'text-amber-900 dark:text-amber-100',
      border: 'border-amber-300/60 dark:border-amber-400/40',
      shadow: 'shadow-amber-200/60 dark:shadow-amber-500/20',
    },
  };

  const config = statusConfig[event.status];

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      whileHover={{ scale: 1.02 }}
      className={`p-1.5 rounded-lg ${config.bg} ${config.text} border ${config.border} shadow-md ${config.shadow} backdrop-blur-sm h-full overflow-hidden`}
    >
      <div className="flex items-center justify-between gap-1 mb-0.5">
        <span className="text-[10px] font-bold leading-tight tabular-nums">
          {dayjs(event.interviewTime).format('HH:mm')}
        </span>
        <span className="px-1 py-px rounded text-[9px] font-semibold leading-tight bg-white/60 dark:bg-black/25">
          {statusLabels[event.status]}
        </span>
      </div>
      <div className="font-display font-semibold text-xs leading-tight mb-0.5 break-words">{event.companyName}</div>
      <div className="text-xs opacity-90 font-medium leading-tight break-words">{event.position}</div>
      {event.roundNumber > 1 && (
        <div className="text-xs opacity-75 mt-0.5 font-medium leading-tight">第{event.roundNumber}轮</div>
      )}
    </motion.div>
  );
};
