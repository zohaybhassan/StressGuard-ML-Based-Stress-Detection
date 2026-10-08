import csv
import io

FIELDNAMES = [
    "record_type", "event_time_utc", "local_date", "timezone",
    "status_or_label", "confidence", "heart_rate_bpm", "sleep_hours",
    "activity_level", "steps_snapshot", "model_version",
    "out_of_training_range", "alert_reason", "alert_high_count",
    "alert_window_size", "alert_dismissed", "feedback_source",
    "feedback_confirmed_stressed", "feedback_severity",
    "workout_end_time_utc", "workout_duration_seconds", "workout_steps",
    "workout_average_heart_rate_bpm", "workout_min_heart_rate_bpm",
    "workout_max_heart_rate_bpm",
]


def render_csv(rows):
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=FIELDNAMES, extrasaction="raise")
    writer.writeheader()
    for row in rows:
        values = {}
        for key in FIELDNAMES:
            value = "" if row.get(key) is None else row.get(key)
            if isinstance(value, str) and value.startswith(("=", "+", "-", "@")):
                value = "'" + value
            values[key] = value
        writer.writerow(values)
    return output.getvalue().encode("utf-8")
