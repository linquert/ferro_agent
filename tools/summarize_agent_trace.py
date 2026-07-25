#!/usr/bin/env python3
"""Render Ferro JSONL events as a compact, credential-free causal trace."""

import argparse
import json
from collections import defaultdict
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parser.add_argument("--latest", action="store_true", help="show only the most recently active thread")
    return parser.parse_args()


class Labels:
    def __init__(self):
        self.values = defaultdict(dict)

    def label(self, kind, value, prefix):
        if not value:
            return "-"
        labels = self.values[kind]
        if value not in labels:
            labels[value] = f"{prefix}{len(labels) + 1}"
        return labels[value]

    def rewrite(self, value):
        if isinstance(value, dict):
            return {key: self.rewrite(item) for key, item in value.items()}
        if isinstance(value, list):
            return [self.rewrite(item) for item in value]
        if isinstance(value, str):
            if value.startswith("obs_"):
                return self.label("observation", value, "O")
            if value.startswith("ferro-screen://obs_"):
                observation = value.removeprefix("ferro-screen://").removesuffix(".png")
                return f"screen:{self.label('observation', observation, 'O')}"
        return value


def compact_json(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True)


def summarize_thread(events, labels):
    first_ms = events[0]["timestampEpochMs"]
    thread = labels.label("thread", events[0]["threadId"], "T")
    title = next(
        (event["payload"].get("title") for event in events if event["payload"]["eventType"] == "thread_started"),
        "",
    )
    print(f"{thread} {title!r}")
    for event in events:
        elapsed = event["timestampEpochMs"] - first_ms
        payload = event["payload"]
        event_type = payload["eventType"]
        prefix = f"  +{elapsed:>6}ms"
        if event_type == "thread_started":
            continue
        if event_type == "turn_started":
            print(f"{prefix} goal {payload['goal']!r}")
        elif event_type == "model_iteration_started":
            iteration = labels.label("iteration", payload["iterationId"], "I")
            summary = payload.get("contextSummary")
            if summary:
                context = (
                    f"items={summary['inputItems']} user={summary['userMessages']} "
                    f"assistant={summary['assistantMessages']} calls={summary['toolCalls']} "
                    f"results={summary['toolResults']} images={summary['images']} "
                    f"tools={summary['advertisedTools']}"
                )
            else:
                context = "context=legacy-unavailable"
            print(f"{prefix} model {iteration} {context}")
        elif event_type == "assistant_message":
            text = payload["text"].replace("\n", " ")
            if len(text) > 180:
                text = text[:177] + "..."
            print(f"{prefix} assistant {text!r}")
        elif event_type == "tool_call":
            call = payload["call"]
            call_label = labels.label("call", call["id"], "C")
            args = labels.rewrite(call.get("arguments", {}))
            print(f"{prefix} call {call_label} {call['name']} {compact_json(args)}")
        elif event_type == "tool_result":
            result = payload["result"]
            call_label = labels.label("call", result["callId"], "C")
            output = labels.rewrite(result.get("output", {}))
            if isinstance(output, dict):
                output.pop("captured_at_epoch_ms", None)
            image_count = len(result.get("attachments", []))
            suffix = f" output={compact_json(output)}"
            if result.get("message"):
                suffix += f" message={result['message']!r}"
            if image_count:
                suffix += f" images={image_count}"
            print(f"{prefix} result {call_label} {result['status']}{suffix}")
        elif event_type == "turn_completed":
            print(f"{prefix} COMPLETE {payload['finalMessage']!r}")
        elif event_type == "turn_failed":
            print(f"{prefix} FAILED {payload['code']} {payload['message']!r}")
        elif event_type == "turn_cancelled":
            print(f"{prefix} CANCELLED {payload['reason']!r}")


def main():
    args = parse_args()
    events = [json.loads(line) for line in args.path.read_text().splitlines() if line.strip()]
    by_thread = defaultdict(list)
    for event in events:
        by_thread[event["threadId"]].append(event)
    threads = sorted(by_thread.values(), key=lambda values: values[-1]["timestampEpochMs"])
    if args.latest and threads:
        threads = threads[-1:]
    labels = Labels()
    for index, thread_events in enumerate(threads):
        if index:
            print()
        summarize_thread(thread_events, labels)


if __name__ == "__main__":
    main()
