export type SamplePeriod = "Day" | "Week" | "Month" | "Year";
export type SampleMetricKey = "stress" | "heart" | "steps" | "sleep";

type SampleMetric = {
  value: string;
  helper: string;
  values: readonly number[];
};

type SampleSnapshot = {
  ticks: readonly [string, string, string, string];
  metrics: Record<SampleMetricKey, SampleMetric>;
};

// Public marketing preview only. These illustrative readings are never user data.
export const sampleTrends: Record<SamplePeriod, SampleSnapshot> = {
  Day: {
    ticks: ["12AM", "6AM", "12PM", "6PM"],
    metrics: {
      stress: { value: "45%", helper: "Example reading", values: [23, 20, 26, 22, 31, 29, 38, 34, 52, 44, 39, 48, 45, 61, 56, 43, 50, 46] },
      heart: { value: "78 bpm", helper: "Example average", values: [68, 72, 69, 75, 73, 78, 74, 82, 77, 84, 79, 86, 81, 85, 77, 82, 76, 78] },
      steps: { value: "4,974", helper: "Example today", values: [1, 1, 2, 2, 3, 4, 5, 4, 7, 8, 6, 10, 12, 11, 9, 13, 12, 11] },
      sleep: { value: "7.5 hrs", helper: "Example last night", values: [3, 4, 4, 5, 6, 5, 7, 6, 8, 7, 9, 8, 10, 9, 11, 10, 12, 13] },
    },
  },
  Week: {
    ticks: ["Mon", "Wed", "Fri", "Sun"],
    metrics: {
      stress: { value: "39%", helper: "7-day average", values: [42, 38, 47, 35, 32, 46, 39] },
      heart: { value: "75 bpm", helper: "7-day average", values: [74, 78, 72, 80, 76, 73, 75] },
      steps: { value: "6,240", helper: "Daily average", values: [5810, 6240, 7020, 4930, 8100, 6940, 6640] },
      sleep: { value: "7.2 hrs", helper: "Nightly average", values: [7.1, 6.7, 7.6, 7.4, 6.8, 8.1, 7.2] },
    },
  },
  Month: {
    ticks: ["Week 1", "Week 2", "Week 3", "Week 4"],
    metrics: {
      stress: { value: "42%", helper: "Monthly average", values: [48, 45, 43, 46, 39, 36, 41, 44, 40, 38, 43, 42] },
      heart: { value: "73 bpm", helper: "Monthly average", values: [77, 76, 74, 72, 75, 71, 70, 73, 72, 74, 71, 73] },
      steps: { value: "6,880", helper: "Daily average", values: [5830, 6240, 6720, 7100, 6930, 7310, 6680, 7510, 7240, 6860, 7790, 7420] },
      sleep: { value: "7.4 hrs", helper: "Nightly average", values: [6.8, 7.2, 7.1, 7.5, 7.7, 7.3, 7.6, 7.2, 7.8, 7.5, 7.4, 7.7] },
    },
  },
  Year: {
    ticks: ["Jan", "Apr", "Jul", "Dec"],
    metrics: {
      stress: { value: "36%", helper: "Yearly average", values: [48, 46, 43, 42, 40, 38, 37, 39, 35, 34, 37, 36] },
      heart: { value: "72 bpm", helper: "Yearly average", values: [78, 77, 75, 76, 74, 73, 72, 74, 71, 70, 72, 72] },
      steps: { value: "7,110", helper: "Daily average", values: [5680, 5900, 6540, 6720, 7110, 7480, 7810, 7630, 7290, 7540, 8010, 7740] },
      sleep: { value: "7.6 hrs", helper: "Nightly average", values: [6.9, 7.0, 7.2, 7.3, 7.5, 7.6, 7.8, 7.6, 7.9, 7.7, 7.8, 7.6] },
    },
  },
};
