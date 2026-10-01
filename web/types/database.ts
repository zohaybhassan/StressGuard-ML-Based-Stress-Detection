export type Json =
  | string
  | number
  | boolean
  | null
  | { [key: string]: Json | undefined }
  | Json[];

type Relationship = {
  foreignKeyName: string;
  columns: string[];
  isOneToOne?: boolean;
  referencedRelation: string;
  referencedColumns: string[];
};

export interface Database {
  public: {
    Tables: {
      profiles: {
        Row: {
          id: string;
          email: string | null;
          display_name: string | null;
          avatar_url: string | null;
          age: number | null;
          gender: "Male" | "Female" | null;
          occupation: string | null;
          bmi_category: "Normal" | "Overweight" | "Obese" | "Underweight" | null;
          password_set: boolean;
          created_at: string;
          updated_at: string;
        };
        Insert: {
          id: string;
          email?: string | null;
          display_name?: string | null;
          avatar_url?: string | null;
          age?: number | null;
          gender?: "Male" | "Female" | null;
          occupation?: string | null;
          bmi_category?: "Normal" | "Overweight" | "Obese" | "Underweight" | null;
          password_set?: boolean;
          created_at?: string;
          updated_at?: string;
        };
        Update: {
          email?: string | null;
          display_name?: string | null;
          avatar_url?: string | null;
          age?: number | null;
          gender?: "Male" | "Female" | null;
          occupation?: string | null;
          bmi_category?: "Normal" | "Overweight" | "Obese" | "Underweight" | null;
          password_set?: boolean;
          updated_at?: string;
        };
        Relationships: [];
      };
      health_checklists: {
        Row: {
          user_id: string;
          smoking: boolean;
          heart_condition: boolean;
          hypertension: boolean;
          diabetes: boolean;
          sleep_disorder: boolean;
          anxiety_history: boolean;
          high_caffeine_use: boolean;
          physically_inactive: boolean;
          updated_at: string;
          created_at: string;
        };
        Insert: {
          user_id: string;
          smoking?: boolean;
          heart_condition?: boolean;
          hypertension?: boolean;
          diabetes?: boolean;
          sleep_disorder?: boolean;
          anxiety_history?: boolean;
          high_caffeine_use?: boolean;
          physically_inactive?: boolean;
          updated_at?: string;
          created_at?: string;
        };
        Update: {
          smoking?: boolean;
          heart_condition?: boolean;
          hypertension?: boolean;
          diabetes?: boolean;
          sleep_disorder?: boolean;
          anxiety_history?: boolean;
          high_caffeine_use?: boolean;
          physically_inactive?: boolean;
          updated_at?: string;
        };
        Relationships: Relationship[];
      };
      stress_predictions: {
        Row: {
          id: number;
          user_id: string;
          recorded_at: string;
          label: string;
          class_index: number;
          confidence: number;
          probabilities: number[];
          model_version: string;
          heart_rate: number;
          daily_steps: number;
          activity_level: number;
          sleep_hours: number;
          out_of_training_range: boolean;
          created_at: string;
        };
        Insert: {
          id?: never;
          user_id: string;
          recorded_at: string;
          label: string;
          class_index: number;
          confidence: number;
          probabilities?: number[];
          model_version: string;
          heart_rate: number;
          daily_steps: number;
          activity_level: number;
          sleep_hours: number;
          out_of_training_range?: boolean;
          created_at?: string;
        };
        Update: Partial<Database["public"]["Tables"]["stress_predictions"]["Insert"]>;
        Relationships: Relationship[];
      };
      alert_events: {
        Row: {
          id: number;
          user_id: string;
          fired_at: string;
          reason: string;
          high_count_in_window: number;
          window_size: number;
          model_version: string;
          dismissed: boolean;
          created_at: string;
        };
        Insert: {
          id?: never;
          user_id: string;
          fired_at: string;
          reason: string;
          high_count_in_window: number;
          window_size: number;
          model_version: string;
          dismissed?: boolean;
          created_at?: string;
        };
        Update: Partial<Database["public"]["Tables"]["alert_events"]["Insert"]>;
        Relationships: Relationship[];
      };
      stress_feedback: {
        Row: {
          id: string;
          user_id: string;
          prompt_source: "high_stress_alert" | "periodic_check_in";
          alert_fired_at: string;
          prediction_recorded_at: string;
          responded_at: string;
          predicted_label: string;
          predicted_class_index: number;
          confidence: number;
          probabilities: Json;
          model_version: string;
          heart_rate: number;
          daily_steps: number;
          activity_level: number;
          sleep_hours: number;
          out_of_training_range: boolean;
          profile_age: number;
          profile_gender: string;
          profile_occupation: string;
          profile_bmi: string;
          confirmed_stressed: boolean;
          severity: number | null;
          created_at: string;
        };
        Insert: {
          id?: string;
          user_id: string;
          prompt_source: "high_stress_alert" | "periodic_check_in";
          alert_fired_at: string;
          prediction_recorded_at: string;
          responded_at: string;
          predicted_label: string;
          predicted_class_index: number;
          confidence: number;
          probabilities: Json;
          model_version: string;
          heart_rate: number;
          daily_steps: number;
          activity_level: number;
          sleep_hours: number;
          out_of_training_range: boolean;
          profile_age: number;
          profile_gender: string;
          profile_occupation: string;
          profile_bmi: string;
          confirmed_stressed: boolean;
          severity?: number | null;
          created_at?: string;
        };
        Update: Partial<Database["public"]["Tables"]["stress_feedback"]["Insert"]>;
        Relationships: Relationship[];
      };
      workout_sessions: {
        Row: {
          id: number;
          user_id: string;
          started_at: string;
          planned_end_at: string;
          ended_at: string | null;
          status: "active" | "paused" | "completed";
          total_paused_ms: number;
          first_steps: number | null;
          last_steps: number | null;
          min_heart_rate: number | null;
          max_heart_rate: number | null;
          heart_rate_sum: number;
          heart_rate_samples: number;
          updated_at: string;
          created_at: string;
        };
        Insert: {
          id?: never;
          user_id: string;
          started_at: string;
          planned_end_at: string;
          ended_at?: string | null;
          status: "active" | "paused" | "completed";
          total_paused_ms?: number;
          first_steps?: number | null;
          last_steps?: number | null;
          min_heart_rate?: number | null;
          max_heart_rate?: number | null;
          heart_rate_sum?: number;
          heart_rate_samples?: number;
          updated_at: string;
          created_at?: string;
        };
        Update: Partial<Database["public"]["Tables"]["workout_sessions"]["Insert"]>;
        Relationships: Relationship[];
      };
    };
    Views: Record<string, never>;
    Functions: Record<string, never>;
    Enums: Record<string, never>;
    CompositeTypes: Record<string, never>;
  };
}

export type Profile = Database["public"]["Tables"]["profiles"]["Row"];
export type StressPrediction =
  Database["public"]["Tables"]["stress_predictions"]["Row"];
export type AlertEvent = Database["public"]["Tables"]["alert_events"]["Row"];
export type StressFeedback =
  Database["public"]["Tables"]["stress_feedback"]["Row"];
export type WorkoutSession =
  Database["public"]["Tables"]["workout_sessions"]["Row"];
