"use client";

import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

type VitalChartGraphicProps = {
  data: { index: number; value: number }[];
  chart: "area" | "bar";
};

export function VitalChartGraphic({ data, chart }: VitalChartGraphicProps) {
  if (chart === "area") {
    return (
      <AreaChart
        width={280}
        height={112}
        data={data}
        margin={{ top: 4, right: 1, bottom: 0, left: 1 }}
      >
        <XAxis dataKey="index" hide />
        <YAxis hide domain={["dataMin", "dataMax"]} />
        <Tooltip content={() => null} />
        <Area
          type="monotone"
          dataKey="value"
          stroke="var(--card-chart)"
          strokeWidth={2.2}
          fill="var(--card-chart-soft)"
          isAnimationActive={false}
        />
      </AreaChart>
    );
  }

  return (
    <BarChart
      width={280}
      height={112}
      data={data}
      margin={{ top: 4, right: 1, bottom: 0, left: 1 }}
    >
      <XAxis dataKey="index" hide />
      <YAxis hide />
      <Bar
        dataKey="value"
        fill="var(--card-chart)"
        radius={[3, 3, 0, 0]}
        isAnimationActive={false}
      />
    </BarChart>
  );
}
