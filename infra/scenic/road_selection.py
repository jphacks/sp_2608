"""Validate the editorial policy before publishing a road registry."""

from datetime import date
import math
from urllib.parse import urlsplit


POLICY = 'review-v2'
ROAD_PUBLISHERS = {'public_authority', 'public_institution', 'tourism_organization', 'tourism_operator', 'road_operator'}


def validate_research_review(record, sources):
    review = record.get('research_review', {})
    if not isinstance(review, dict) or review.get('method') != 'source-research-v1':
        raise ValueError('Source research review required: ' + record['id'])
    date.fromisoformat(review.get('checked_on', ''))
    for field in ('selection_reason', 'section_reason', 'duplicate_check', 'cautions'):
        if not isinstance(review.get(field), str) or not review[field].strip():
            raise ValueError('Missing research finding: ' + record['id'] + '/' + field)
    findings = review.get('findings', [])
    if not isinstance(findings, list) or not findings:
        raise ValueError('Road-specific findings required: ' + record['id'])
    for finding in findings:
        if (not isinstance(finding, dict)
                or finding.get('source_id') not in record['road_introduction_source_ids']
                or finding.get('source_id') not in sources
                or not isinstance(finding.get('locator'), str) or not finding['locator'].strip()
                or not isinstance(finding.get('fact'), str) or not finding['fact'].strip()):
            raise ValueError('Invalid research finding: ' + record['id'])
    evidence = review.get('driving_evidence')
    if evidence not in ('explicit_recommendation', 'described_road_landscape'):
        raise ValueError('Invalid driving evidence: ' + record['id'])
    if record['rank'] in ('recommended', 'panorama') and evidence != 'explicit_recommendation':
        raise ValueError('Driving recommendation required for rank: ' + record['id'])


def validate_road_introduction(record, sources):
    introductions = record.get('road_introduction_source_ids', [])
    if (not isinstance(introductions, list) or not introductions
            or any(not isinstance(ident, str) or not ident for ident in introductions)
            or len(set(introductions)) != len(introductions)):
        raise ValueError('Registered roads require a road introduction: ' + record['id'])
    for ident in introductions:
        source = sources.get(ident, {})
        url = urlsplit(source.get('source_url', ''))
        if (ident not in record['source_ids'] or source.get('kind', 'primary') != 'primary'
                or source.get('publisher_type') not in ROAD_PUBLISHERS
                or source.get('subject') != 'road_introduction'
                or url.scheme != 'https' or not url.hostname or url.username or url.password):
            raise ValueError('Invalid road introduction source: ' + record['id'] + '/' + ident)


def selection_basis(record, sources):
    primary, editorial, reviews = set(), set(), []
    for ident in record['source_ids']:
        source = sources[ident]
        group = source.get('independence_group', source['publisher'])
        kind = source.get('kind', 'primary')
        if kind == 'primary' and ident in record.get('road_introduction_source_ids', []):
            primary.add(group)
        elif kind == 'editorial':
            editorial.add(group)
    for review in record.get('review_observations', []):
        ident = review['source_id']
        if ident not in record['source_ids'] or sources[ident].get('kind') != 'reviews':
            raise ValueError('Review observation requires a linked review source')
        date.fromisoformat(review['checked_on'])
        rating, count = review['rating'], review['count']
        if (isinstance(rating, bool) or not isinstance(rating, (int, float)) or not math.isfinite(rating)
                or not 1 <= rating <= 5 or isinstance(count, bool) or not isinstance(count, int) or count < 1
                or review['subject'] not in ('road', 'pass', 'attraction')):
            raise ValueError('Invalid review observation')
        adjusted = (count * rating + 20 * 3.5) / (count + 20)
        if review['subject'] == 'road' and count >= 20 and adjusted >= 3.9:
            reviews.append(sources[ident].get('independence_group', sources[ident]['publisher']))
    corroborating = (editorial | set(reviews)) - primary
    return {'policy': POLICY, 'primary_publishers': sorted(primary),
            'editorial_publishers': sorted(editorial), 'qualifying_review_publishers': sorted(set(reviews)),
            'panorama_eligible': bool(primary) and (len(primary) >= 2 or bool(corroborating))}


def validate_selection(manifest, sources):
    if manifest.get('selection_policy') != POLICY:
        raise ValueError('Unsupported road selection policy')
    required_since = manifest.get('research_review_required_since')
    if required_since:
        required_since = date.fromisoformat(required_since)
    for record in manifest['roads']:
        if not record.get('road_introduction_source_ids') and record.get('registration_hold_reasons'):
            continue
        validate_road_introduction(record, sources)
        review_required = required_since and date.fromisoformat(record.get('reviewed_at', '')) >= required_since
        if record.get('research_review') or review_required:
            validate_research_review(record, sources)
        basis = selection_basis(record, sources)
        if not basis['primary_publishers']:
            raise ValueError('Recommended roads require a primary source: ' + record['id'])
        if record['rank'] == 'panorama' and not basis['panorama_eligible']:
            raise ValueError('Insufficient panorama evidence: ' + record['id'])
        record['selection_basis'] = basis
