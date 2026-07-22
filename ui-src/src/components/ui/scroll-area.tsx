import * as React from "react";
import { cn } from "@/lib/utils";

/** Lightweight scroll container (no extra radix dep). */
export function ScrollArea({
  className,
  children,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={cn("overflow-auto", className)} {...props}>
      {children}
    </div>
  );
}
