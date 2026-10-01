import { z } from "zod";

const publicSupabaseSchema = z.object({
  url: z.url().refine((value) => value.startsWith("https://"), {
    message: "Supabase URL must use HTTPS",
  }),
  publishableKey: z.string().min(1),
});

export type PublicSupabaseConfig = z.infer<typeof publicSupabaseSchema>;

export function getPublicSupabaseConfig(): PublicSupabaseConfig | null {
  const result = publicSupabaseSchema.safeParse({
    url: process.env.NEXT_PUBLIC_SUPABASE_URL,
    publishableKey: process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY,
  });

  return result.success ? result.data : null;
}
