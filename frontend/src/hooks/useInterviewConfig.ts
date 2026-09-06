import { useState, useEffect } from 'react';
import { skillApi, type SkillDTO } from '../api/skill';
import { historyApi, type ResumeListItem } from '../api/history';
import { getSkillIcon } from '../utils/skillIcons';

export type InterviewMode = 'text' | 'voice';
export type Difficulty = 'junior' | 'mid' | 'senior';

export const DIFFICULTY_OPTIONS: { value: Difficulty; label: string; desc: string }[] = [
  { value: 'junior', label: '校招', desc: '0-1 年' },
  { value: 'mid', label: '中级', desc: '1-3 年' },
  { value: 'senior', label: '高级', desc: '3 年+' },
];

export const DEFAULT_SKILL_ID = 'java-backend';
export const CUSTOM_SKILL_ID = 'custom';
export const DEFAULT_LLM_PROVIDER = '';

export interface InterviewConfigState {
  mode: InterviewMode;
  skillId: string;
  difficulty: Difficulty;
  skills: SkillDTO[];
  loadingSkills: boolean;
  showMore: boolean;
  resumeId: number | undefined;
  resumes: ResumeListItem[];
  llmProvider: string;
  questionCount: number;
  plannedDuration: number;
  jdText: string;
}

export function useInterviewConfig(options?: {
  defaultMode?: InterviewMode;
  defaultResumeId?: number;
  autoLoad?: boolean;
}) {
  const { defaultMode = 'text', defaultResumeId, autoLoad = true } = options ?? {};

  const [mode, setMode] = useState<InterviewMode>(defaultMode);
  const [skillId, setSkillId] = useState(DEFAULT_SKILL_ID);
  const [difficulty, setDifficulty] = useState<Difficulty>('mid');
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [loadingSkills, setLoadingSkills] = useState(false);
  const [showMore, setShowMore] = useState(false);
  const [resumeId, setResumeId] = useState<number | undefined>(undefined);
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [questionCount, setQuestionCount] = useState<number>(6);
  const [plannedDuration, setPlannedDuration] = useState(30);
  const [llmProvider, setLlmProvider] = useState(DEFAULT_LLM_PROVIDER);
  const [jdText, setJdText] = useState('');

  const loadSkills = async () => {
    setLoadingSkills(true);
    try {
      const data = await skillApi.listSkills();
      setSkills(data);
      return data;
    } catch (err) {
      console.error('Failed to load skills:', err);
      return [];
    } finally {
      setLoadingSkills(false);
    }
  };

  const loadResumes = async () => {
    try {
      const data = await historyApi.getResumes();
      setResumes(data);
    } catch (err) {
      console.error('Failed to load resumes:', err);
    }
  };

  useEffect(() => {
    if (autoLoad) {
      setMode(defaultMode);
      if (defaultResumeId != null) {
        setResumeId(defaultResumeId);
        setShowMore(true);
      }
      loadSkills();
      loadResumes();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoLoad, defaultMode, defaultResumeId]);

  return {
    // State
    mode, setMode,
    skillId, setSkillId,
    difficulty, setDifficulty,
    skills, setSkills,
    loadingSkills,
    showMore, setShowMore,
    resumeId, setResumeId,
    resumes,
    questionCount, setQuestionCount,
    plannedDuration, setPlannedDuration,
    llmProvider, setLlmProvider,
    jdText, setJdText,
    // Actions
    loadSkills,
    loadResumes,
    // Helpers
    getSkillIcon,
    get selectedSkill() { return skills.find(s => s.id === skillId); },
  };
}
