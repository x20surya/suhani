import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** shadcn's class helper. The vendored Aura components expect it. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
