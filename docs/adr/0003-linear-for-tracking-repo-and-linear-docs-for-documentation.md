# 0003. Linear for tracking; repo docs and Linear Docs for documentation

Date: 2026-11-10
Status: Accepted

## Context

The project needs somewhere to plan work (issues, milestones, cycles) and somewhere to keep documentation. Documentation that describes the code goes stale unless it changes together with the code. Planning and product thinking change on a different rhythm and don't belong in code review.

## Options considered

- **Linear**: fast issue tracker with projects, milestones, cycles, project docs, and a GitHub integration that links branches and PRs and closes issues on merge.
- **Jira + Confluence**: the common enterprise pair; heavier to set up and use for a one-person project.
- **GitHub Issues and Projects**: next to the code, but weaker at cycles, milestones and planning docs.
- **Everything in the repo** (Markdown for plans and specs too): one place, but product notes then go through code review, and planning edits clutter the history.

## Decision

Track work in Linear (team `ALB`, project ask-my-docs). Split documentation by what it describes:

- **Describes how the code works → the repo**, changed in the same PR as the code: [README](../../README.md), [architecture](../architecture.md), these ADRs, the OpenAPI document (springdoc), per-ticket plans in [`docs/plans/`](../plans), and agent instructions ([CLAUDE.md](../../CLAUDE.md)).
- **Planning or product thinking → Linear**: issue descriptions, project docs such as the product spec, the roadmap, and ideas.

Link between the two; never copy content from one to the other.

## Consequences

- Repo docs are reviewed with the code and checked in CI (`scripts/test-docs.sh`).
- A decision that starts in a Linear issue and turns out to be costly to reverse gets an ADR here; the issue links to the ADR.
- Branches use each issue's Linear branch name and PRs say `Fixes ALB-<N>`, so Linear closes the issue on merge.
- Linear is a hosted service: planning content lives outside the repo and its history.
