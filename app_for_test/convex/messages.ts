import { query, mutation } from "./_generated/server";
import { v } from "convex/values";
import schema from "./schema";

export const list = query({
  args: {},
  handler: async (ctx) => {
    // Grab the most recent messages.
    const messages = await ctx.db.query("messages").order("desc").take(100);
    // Reverse the list so that it's in a chronological order.
    return messages.reverse();
  },
});

export const send = mutation({
  args: { body: v.string(), author: v.string() },
  handler: async (ctx, { body, author }) => {
    // Send a new message.
    await ctx.db.insert("messages", { body, author });
  },
});

export const clearAll = mutation(async (ctx) => {
    for (const table of Object.keys(schema.tables)) {
      const docs = await ctx.db.query(table as any).collect();
      await Promise.all(docs.map((doc) => ctx.db.delete(doc._id)));
    }
    const scheduled = await ctx.db.system.query("_scheduled_functions").collect();
    await Promise.all(scheduled.map((s) => ctx.scheduler.cancel(s._id)));
    const storedFiles = await ctx.db.system.query("_storage").collect();
    await Promise.all(storedFiles.map((s) => ctx.storage.delete(s._id)));
  })