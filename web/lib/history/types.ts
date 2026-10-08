export const HISTORY_PAGE_SIZE = 20;
export const HISTORY_MAX_PAGE = 100;

export type HistoryTab = "predictions" | "alerts" | "workouts";
export type PredictionLabelFilter = "all" | "stressed" | "not_stressed" | "high_stress";
export type InputRangeFilter = "all" | "warning" | "within";
export type AlertRecordFilter = "all" | "alerts" | "feedback";
export type BooleanFilter = "all" | "yes" | "no";
export type FeedbackSourceFilter = "all" | "high_stress_alert" | "periodic_check_in";
export type WorkoutStatusFilter = "all" | "active" | "paused" | "completed";

export type HistoryFilters = {
  tab: HistoryTab;
  page: number;
  from: string | null;
  to: string | null;
  predictionLabel: PredictionLabelFilter;
  inputRange: InputRangeFilter;
  alertRecords: AlertRecordFilter;
  dismissed: BooleanFilter;
  feedbackSource: FeedbackSourceFilter;
  workoutStatus: WorkoutStatusFilter;
  detail: string | null;
};

export type PredictionHistoryItem = {
  kind: "prediction";
  id: number;
  occurredAt: string;
  label: string;
  classIndex: number;
  confidence: number;
  heartRate: number;
  sleepHours: number;
  activityLevel: number;
  dailySteps: number;
  modelVersion: string;
  outOfTrainingRange: boolean;
};

export type AlertHistoryItem = {
  kind: "alert";
  id: number;
  occurredAt: string;
  reason: string;
  highCountInWindow: number;
  windowSize: number;
  modelVersion: string;
  dismissed: boolean;
};

export type FeedbackHistoryItem = {
  kind: "feedback";
  id: string;
  occurredAt: string;
  alertFiredAt: string;
  predictionRecordedAt: string;
  promptSource: "high_stress_alert" | "periodic_check_in";
  predictedLabel: string;
  predictedClassIndex: number;
  confidence: number;
  confirmedStressed: boolean;
  severity: number | null;
  heartRate: number;
  dailySteps: number;
  activityLevel: number;
  sleepHours: number;
  modelVersion: string;
  outOfTrainingRange: boolean;
};

export type WorkoutHistoryItem = {
  kind: "workout";
  id: number;
  occurredAt: string;
  plannedEndAt: string;
  endedAt: string | null;
  status: "active" | "paused" | "completed";
  durationMs: number | null;
  stepCount: number | null;
  averageHeartRate: number | null;
  minHeartRate: number | null;
  maxHeartRate: number | null;
  totalPausedMs: number;
};

export type HistoryItem =
  | PredictionHistoryItem
  | AlertHistoryItem
  | FeedbackHistoryItem
  | WorkoutHistoryItem;

export type HistoryPageData = {
  tab: HistoryTab;
  items: HistoryItem[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
  hasPrevious: boolean;
  hasNext: boolean;
};

export type HistoryLoadResult =
  | { status: "ready"; data: HistoryPageData }
  | { status: "unauthorized" }
  | { status: "configuration-error" }
  | { status: "query-error" };
