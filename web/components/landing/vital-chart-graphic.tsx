"use client";

import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

type VitalChartGraphicProps = {
  data: { index: number; value: number }[];
  chart: "area" | "bar";
  animate: boolean;
  animationBegin: number;
};

export function VitalChartGraphic({
  data,
  chart,
  animate,
  animationBegin,
}: VitalChartGraphicProps) {
  if (chart === "area") {
    return (
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 4, right: 1, bottom: 0, left: 1 }}>
          <XAxis dataKey="index" hide />
          <YAxis hide domain={["dataMin", "dataMax"]} />
          <Tooltip content={() => null} />
          <Area
            type="monotone"
            dataKey="value"
            stroke="var(--card-chart)"
            strokeWidth={2.6}
            fill="var(--card-chart-soft)"
            fillOpacity={0.72}
            isAnimationActive={animate}
            animationBegin={animationBegin}
            animationDuration={620}
            animationEasing="ease-out"
          />
        </AreaChart>
      </ResponsiveContainer>
    );
  }

  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data} margin={{ top: 4, right: 1, bottom: 0, left: 1 }}>
        <XAxis dataKey="index" hide />
        <YAxis hide />
        <Bar
          dataKey="value"
          fill="var(--card-chart)"
          radius={[4, 4, 0, 0]}
          isAnimationActive={animate}
          animationBegin={animationBegin}
          animationDuration={620}
          animationEasing="ease-out"
        />
      </BarChart>
    </ResponsiveContainer>
  );
}
